package datadog.trace.agent.tooling.checker;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

/**
 * A bytebuddy matcher that matches if expected references (classes, fields, methods, visibility) are present on the classpath.
 */
public class ReferenceMatcher implements AgentBuilder.RawMatcher {
  // class Reference (Leverage Bytebuddy if possible: MethodDescription)
  //   - source[] (bytecode sources)
  //   - className
  //   - methodReferences[]
  //   - fieldReferences[]
  //   - merge(Reference anotherReference) -> mergedReference
  // class MethodReference
  //   - methodSignature
  // class FieldReference
  //   - fieldSignature

  // TODO: Cache safe and unsafe classloaders

  // list of unique references (by class name)

  // take a list of references
  public ReferenceMatcher() {
    // TODO: pass in references
  }


  @Override
  public boolean matches(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
    return matches(classLoader);
  }

  public boolean matches(ClassLoader loader) {
    return getMismatchedReferenceSources(loader).size() == 0;
  }

  public List<String> getMismatchedReferenceSources(ClassLoader loader) {
    if (loader == BOOTSTRAP_LOADER) {
      // TODO
    }
    final List<String> mismatchedReferences = new ArrayList<>(0);
    // for each reference:
    // - assert class name is present
    // - assert all methods present
    // - assert all fields present
    return mismatchedReferences;
  }

  public ElementMatcher<? super MethodDescription> createElementMatcher(final ElementMatcher<? super MethodDescription> matcher, final String adviceClassName) {
    return new ElementMatcher<MethodDescription>() {
      public boolean matches(MethodDescription target) {
        return matcher.matches(target);
      }
    };
  }

  public Transformer assertSafeTransformation(String... adviceClassNames) {
    return new Transformer() {
      @Override
      public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder, TypeDescription typeDescription, ClassLoader classLoader, JavaModule module) {
        return builder;
      }
    };
  }

  public static ReferenceMatcher create(String adviceClassName) {
    return null;
  }
}
