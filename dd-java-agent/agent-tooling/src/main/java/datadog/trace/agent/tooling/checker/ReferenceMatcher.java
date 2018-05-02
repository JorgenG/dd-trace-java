package datadog.trace.agent.tooling.checker;

import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

import datadog.trace.agent.tooling.Utils;
import datadog.trace.bootstrap.DatadogClassLoader;
import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.*;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.utility.JavaModule;

/**
 * A bytebuddy matcher that matches if expected references (classes, fields, methods, visibility)
 * are present on the classpath.
 */
public class ReferenceMatcher implements AgentBuilder.RawMatcher {
  // TODO: Cache safe and unsafe classloaders

  // list of unique references (by class name)
  Map<String, Reference> references = new HashMap<>();
  Set<String> referenceSources = new HashSet<>();

  // take a list of references
  public ReferenceMatcher() {
    // TODO: pass in references
  }

  // TODO: Don't add references if instrumentation is already in referenceSources
  private void addReferencesFrom(String instrumentationClassName) {
    try {
      final InputStream in =
          ReferenceMatcher.class
              .getClassLoader()
              .getResourceAsStream(Utils.getResourceName(instrumentationClassName));
      try {
        final AdviceReferenceVisitor cv = new AdviceReferenceVisitor(null);
        final ClassReader reader = new ClassReader(in);
        reader.accept(cv, ClassReader.SKIP_DEBUG);

        Map<String, Reference> instrumentationReferences = cv.getReferences();
        for (Map.Entry<String, Reference> entry : instrumentationReferences.entrySet()) {
          if (references.containsKey(entry.getKey())) {
            references.put(entry.getKey(), references.get(entry.getKey()).merge(entry.getValue()));
          } else {
            references.put(entry.getKey(), entry.getValue());
          }
        }

      } finally {
        in.close();
      }
    } catch (IOException ioe) {
      throw new IllegalStateException(ioe);
    }
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    return matches(classLoader);
  }

  public boolean matches(ClassLoader loader) {
    return getMismatchedReferenceSources(loader).size() == 0;
  }

  public List<Reference.Source> getMismatchedReferenceSources(ClassLoader loader) {
    if (loader == BOOTSTRAP_LOADER) {
      loader = ((DatadogClassLoader) Utils.getAgentClassLoader()).getBootstrapResourceLocator();
    }
    final List<Reference.Source> mismatchedReferences = new ArrayList<>(0);
    for (Reference reference : references.values()) {
      mismatchedReferences.addAll(reference.checkMatch(loader));
    }
    // TODO: log mismatches
    for (Reference.Source mismatch : mismatchedReferences) {
      // TODO: log more info about why mismatch occurred. Missing method, missing field, signature mismatch.
      System.out.println(" Mismatched reference: " + mismatch.getName() + ":" + mismatch.getLine());
    }
    return mismatchedReferences;
  }

  public Transformer assertSafeTransformation(String... adviceClassNames) {
    // load or check cache for advice class names
    for (String adviceClass : adviceClassNames) {
      // TODO: cache during compilation
      if (!referenceSources.contains(adviceClassNames)) {
        referenceSources.add(adviceClass);
        System.out.println("FIXME: CREATING REFERENCES FOR::: " + adviceClass);
        for (Map.Entry<String, Reference> entry :
            AdviceReferenceVisitor.createReferencesFrom(
                    adviceClass, ReferenceMatcher.class.getClassLoader())
                .entrySet()) {
          if (references.containsKey(entry.getKey())) {
            references.put(entry.getKey(), references.get(entry.getKey()).merge(entry.getValue()));
          } else {
            references.put(entry.getKey(), entry.getValue());
          }
        }
      }
    }

    return new Transformer() {
      @Override
      public DynamicType.Builder<?> transform(
          DynamicType.Builder<?> builder,
          TypeDescription typeDescription,
          ClassLoader classLoader,
          JavaModule module) {
        if (ReferenceMatcher.this.matches(classLoader)) {
          return builder;
        } else {
          // TODO: make custom exception type and add more descriptive logging.
          throw new RuntimeException("Failed to match");
        }
      }
    };
  }
}
