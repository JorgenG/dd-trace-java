package datadog.trace.agent.tooling.checker;

import java.util.List;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;

/**
 * A reference to a single class file.
 */
public class Reference {
  private Source[] sources;
  private String internalClassName;
  private Field[] fields;
  private Method[] methods;

  /**
   * TODO: doc
   *
   * @param anotherReference A reference to the same class
   * @return a new Reference which merges the two references
   */
  public Reference merge(Reference anotherReference) {
    throw new RuntimeException("TODO");
    // TODO: throw exception if references are incompatible
  }

  /**
   * Check this reference against a classloader's classpath.
   *
   * @param loader
   * @return A list of mismatched sources. A list of size 0 means the reference matches the class.
   */
  public List<Source> checkMatch(ClassLoader loader) {
    if (loader == BOOTSTRAP_CLASSLOADER) {
      throw new IllegalStateException("Cannot directly check against bootstrap classloader");
    }
    throw new RuntimeException("TODO");
  }

  private class Method {
    // sources
    // signature
  }

  private class Field {
    // sources
    // signature
  }

  public static class Source {
    private final String name;
    private final int line;

    public Source(String name, int line) {
      this.name = name;
      this.line = line;
    }

    public String getName() {
      return name;
    }

    public int getLine() {
      return line;
    }
  }
}
