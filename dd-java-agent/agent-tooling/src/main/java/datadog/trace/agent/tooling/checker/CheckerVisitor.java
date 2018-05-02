package datadog.trace.agent.tooling.checker;

import java.util.Collection;
import java.util.HashSet;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/**
 * Visit instrumentation classes. For each decorator, add a matcher which asserts that the
 * classloader being instrumented can safely load all references made by instrumentation.
 */
public class CheckerVisitor implements AsmVisitorWrapper {
  // TODO PLAN
  // - checker must run in matcher phase (before helpers are injected)

  // BYTECODE: add classpath matcher
  // - Phase1: find advice
  //   - find all locations of `.advice`
  //   - collect: className for all instrumentation advice classes. throw an exception if advice name can't be determined.
  //   - collect: each insertion point for `and` matcher, and the list of advice classes to check
  // - Phase2: write matcher
  //   - insert matcher just before first transformer using list collected in first pass (maybe use a label??)

  // BYTECODE: initialize advice references in constructor
  // - statically create references in <init> or <clinit> and pass into checker constructor

  // Implement reference matching logic

  @Override
  public int mergeWriter(int flags) {
    return flags | ClassWriter.COMPUTE_MAXS;
  }

  @Override
  public int mergeReader(int flags) {
    return flags;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags) {
    return new InsertSafetyMatcher(classVisitor);
  }

  public static class InsertSafetyMatcher extends ClassVisitor {
    public InsertSafetyMatcher(ClassVisitor classVisitor) {
      super(Opcodes.ASM6, classVisitor);
    }

    public String instrumentationClassName;

    @Override
    public void visit(
        final int version,
        final int access,
        final String name,
        final String signature,
        final String superName,
        final String[] interfaces) {
      this.instrumentationClassName = name;
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
        final int access,
        final String name,
        final String descriptor,
        final String signature,
        final String[] exceptions) {
      MethodVisitor methodVisitor =
          super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("<init>".equals(name)) {
        methodVisitor = new InitializeFieldVisitor(methodVisitor);
      }
      // return new ReplaceIsSafeVisitor(methodVisitor);
      return new InsertCheckerTransformer(methodVisitor);
    }

    @Override
    public void visitEnd() {
      super.visitField(
          Opcodes.ACC_PUBLIC,
          "referenceMatcher",
          Type.getDescriptor(ReferenceMatcher.class),
          null,
          null);
      super.visitEnd();
    }

    public class InsertCheckerTransformer extends MethodVisitor {
      // it would be nice to manage the state with an enum, but that requires this class to be non-static
      private final int INIT = 0;
      // SomeClass
      private final int PREVIOUS_INSTRUCTION_LDC = 1;
      // SomeClass.getName()
      private final int PREVIOUS_INSTRUCTION_GET_CLASS_NAME = 2;

      private String lastClassLDC = null;

      private Collection<String> adviceClassNames = new HashSet<>();
      private int STATE = INIT;

      public InsertCheckerTransformer(MethodVisitor methodVisitor) {
        super(Opcodes.ASM6, methodVisitor);
      }

      public void reset() {
        STATE = INIT;
        lastClassLDC = null;
        adviceClassNames.clear();
      }

      @Override
      public void visitMethodInsn(
          final int opcode,
          final String owner,
          final String name,
          final String descriptor,
          final boolean isInterface) {
        if (name.equals("getName")) {
          if (STATE == PREVIOUS_INSTRUCTION_LDC) {
            STATE = PREVIOUS_INSTRUCTION_GET_CLASS_NAME;
          }
        } else if (name.equals("advice")) {
          if (STATE == PREVIOUS_INSTRUCTION_GET_CLASS_NAME) {
            adviceClassNames.add(lastClassLDC);
          }
          // add last LDC/ToString to adivce list
        } else if (name.equals("asDecorator")) {
          this.visitVarInsn(Opcodes.ALOAD, 0);
          this.visitFieldInsn(
              Opcodes.GETFIELD,
              instrumentationClassName,
              "referenceMatcher",
              Type.getDescriptor(ReferenceMatcher.class));
          mv.visitIntInsn(Opcodes.BIPUSH, adviceClassNames.size());
          mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
          int i = 0;
          for (String adviceClassName : adviceClassNames) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitIntInsn(Opcodes.BIPUSH, i);
            mv.visitLdcInsn(adviceClassName);
            mv.visitInsn(Opcodes.AASTORE);
            ++i;
          }
          mv.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "datadog/trace/agent/tooling/checker/ReferenceMatcher",
              "assertSafeTransformation",
              "([Ljava/lang/String;)Lnet/bytebuddy/agent/builder/AgentBuilder$Transformer;",
              false);
          mv.visitMethodInsn(
              Opcodes.INVOKEINTERFACE,
              "net/bytebuddy/agent/builder/AgentBuilder$Identified$Narrowable",
              "transform",
              "(Lnet/bytebuddy/agent/builder/AgentBuilder$Transformer;)Lnet/bytebuddy/agent/builder/AgentBuilder$Identified$Extendable;",
              true);
          reset();
        } else {
          STATE = INIT;
          lastClassLDC = null;
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }

      @Override
      public void visitLdcInsn(final Object value) {
        if (value instanceof Type) {
          Type type = (Type) value;
          if (type.getSort() == Type.OBJECT) {
            lastClassLDC = type.getClassName();
            STATE = PREVIOUS_INSTRUCTION_LDC;
            type.getClassName();
          }
        }
        super.visitLdcInsn(value);
      }
    }

    /**
     * Replace:<br>
     * &nbsp&nbsp advice(elementMatcher, className)<br>
     * Into:<br>
     * &nbsp&nbsp advice(this.referenceMatcher.createElementMatcher(elementMatcher, className),
     * className)
     */
    public class ReplaceIsSafeVisitor extends MethodVisitor {
      public ReplaceIsSafeVisitor(MethodVisitor methodVisitor) {
        super(Opcodes.ASM6, methodVisitor);
      }

      @Override
      public void visitMethodInsn(
          final int opcode,
          final String owner,
          final String name,
          final String descriptor,
          final boolean isInterface) {
        if (name.equals("advice")) {
          // stack: [class, matcher]
          this.visitVarInsn(Opcodes.ALOAD, 0);
          // stack: [this, class, matcher]
          this.visitFieldInsn(
              Opcodes.GETFIELD,
              instrumentationClassName,
              "referenceMatcher",
              Type.getDescriptor(ReferenceMatcher.class));
          // stack: [referenceMatcher, class, matcher]
          this.visitInsn(Opcodes.DUP2_X1);
          // stack: [referenceMatcher, class, matcher, referenceMatcher, class]
          this.visitInsn(Opcodes.POP);
          // stack: [class, matcher, referenceMatcher, class]
          this.visitMethodInsn(
              Opcodes.INVOKEVIRTUAL,
              "datadog/trace/agent/tooling/checker/ReferenceMatcher",
              "createElementMatcher",
              "(Lnet/bytebuddy/matcher/ElementMatcher;Ljava/lang/String;)Lnet/bytebuddy/matcher/ElementMatcher;",
              false);
          // stack: [safe-matcher, class]
          this.visitInsn(Opcodes.SWAP);
          // stack: [class, safe-matcher]
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }

    /** Append a field initializer to the end of a method. */
    public class InitializeFieldVisitor extends MethodVisitor {
      public InitializeFieldVisitor(MethodVisitor methodVisitor) {
        super(Opcodes.ASM6, methodVisitor);
      }

      @Override
      public void visitInsn(final int opcode) {
        if (opcode == Opcodes.RETURN) {
          super.visitVarInsn(Opcodes.ALOAD, 0);
          mv.visitTypeInsn(Opcodes.NEW, "datadog/trace/agent/tooling/checker/ReferenceMatcher");
          mv.visitInsn(Opcodes.DUP);
          mv.visitMethodInsn(
              Opcodes.INVOKESPECIAL,
              "datadog/trace/agent/tooling/checker/ReferenceMatcher",
              "<init>",
              "()V",
              false);
          super.visitFieldInsn(
              Opcodes.PUTFIELD,
              instrumentationClassName.replace('.', '/'),
              "referenceMatcher",
              Type.getDescriptor(ReferenceMatcher.class));
        }
        super.visitInsn(opcode);
      }
    }
  }
}
