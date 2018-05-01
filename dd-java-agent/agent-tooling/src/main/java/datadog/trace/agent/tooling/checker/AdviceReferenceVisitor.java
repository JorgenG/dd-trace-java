package datadog.trace.agent.tooling.checker;

import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.Opcodes;

import java.util.HashMap;
import java.util.Map;

/**
 * Visit an advice class and collect a set of references for every reference created by methods annotated with @Advice
 */
public class AdviceReferenceVisitor extends ClassVisitor {
  private Map<String, Reference> references = new HashMap<>();

  public AdviceReferenceVisitor(ClassVisitor classVisitor) {
    super(Opcodes.ASM6, classVisitor);
  }

  public static Map<String, Reference> createReferencesFrom(String className, ClassLoader loader) {
    // set<String> referenceSources
    // load class resource
    // vist and generate references.
    // for each reference
    //   if (not in ref sources): recursively add all references
    throw new RuntimeException("TODO");
  }

  public Map<String, Reference> getReferences() {
    return references;
  }
}
