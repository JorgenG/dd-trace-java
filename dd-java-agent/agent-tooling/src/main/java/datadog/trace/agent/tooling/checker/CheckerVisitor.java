package datadog.trace.agent.tooling.checker;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.*;
import net.bytebuddy.pool.TypePool;

/**
 * Visit instrumentation classes. For each decorator, add a matcher which asserts that the classloader being instrumented can safely load all references made by instrumentation.
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
  public ClassVisitor wrap(TypeDescription instrumentedType, ClassVisitor classVisitor, Implementation.Context implementationContext, TypePool typePool, FieldList<FieldDescription.InDefinedShape> fields, MethodList<?> methods, int writerFlags, int readerFlags) {
    return new InsertSafetyMatcher(classVisitor);
  }

  public static class InsertSafetyMatcher extends ClassVisitor {
    public InsertSafetyMatcher(ClassVisitor classVisitor) {
      super(Opcodes.ASM6, classVisitor);
    }
    public String instrumentationClassName;


    @Override
    public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
      this.instrumentationClassName = name;
      super.visit(version, access, name, signature, superName, interfaces);
    }


    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature, final String[] exceptions) {
      MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
      if ("<init>".equals(name)) {
        methodVisitor = new InitializeFieldVisitor(methodVisitor);
      }
      return new ReplaceIsSafeVisitor(methodVisitor);
    }

    @Override
    public void visitEnd() {
      super.visitField(Opcodes.ACC_PUBLIC, "adviceChecker", Type.getDescriptor(ReferenceMatcher.class), null, null);
      super.visitEnd();
    }

    public class ReplaceIsSafeVisitor extends MethodVisitor {
      public ReplaceIsSafeVisitor(MethodVisitor methodVisitor) {
        super(Opcodes.ASM6, methodVisitor);
      }

      /**
       * Turns this bytecode:<br/>
       * &nbsp&nbsp advice(elementMatcher, className)<br/>
       * Into:<br/>
       *  &nbsp&nbsp advice(this.adviceChecker.createElementMatcher(elementMatcher, className), className)
       */
      @Override
      public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor, final boolean isInterface) {
        if(name.equals("advice")) {
          // stack: [class, matcher]
          this.visitVarInsn(Opcodes.ALOAD, 0);
          // stack: [this, class, matcher]
          this.visitFieldInsn(Opcodes.GETFIELD, instrumentationClassName, "adviceChecker", Type.getDescriptor(ReferenceMatcher.class));
          // stack: [adviceChecker, class, matcher]
          this.visitInsn(Opcodes.DUP2_X1);
          // stack: [adviceChecker, class, matcher, adviceChecker, class]
          this.visitInsn(Opcodes.POP);
          // stack: [class, matcher, adviceChecker, class]
          this.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "datadog/trace/agent/tooling/checker/ReferenceMatcher", "createElementMatcher", "(Lnet/bytebuddy/matcher/ElementMatcher;Ljava/lang/String;)Lnet/bytebuddy/matcher/ElementMatcher;", false);
          // stack: [safe-matcher, class]
          this.visitInsn(Opcodes.SWAP);
          // stack: [class, safe-matcher]
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
      }
    }

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
          mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "datadog/trace/agent/tooling/checker/ReferenceMatcher", "<init>", "()V", false);
          super.visitFieldInsn(Opcodes.PUTFIELD,
            instrumentationClassName.replace('.', '/'),
            "adviceChecker",
            Type.getDescriptor(ReferenceMatcher.class));
        }
        super.visitInsn(opcode);
      }
    }
  }
}
