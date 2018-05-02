package datadog.trace.agent.tooling.checker;

import datadog.trace.agent.tooling.Utils;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.BOOTSTRAP_CLASSLOADER;

import java.util.*;

/** A reference to a single class file. */
public class Reference {
  private Source[] sources;
  private String className;
  private String superName;
  private int flags = 0;
  private String[] interfaceNames;
  private Field[] fields;
  private Method[] methods;

  public Reference(String className, String superName, String[] interfaces) {
    this.className = className;
    this.superName = superName;
    this.interfaceNames = interfaces;
  }

  public String getClassName() {
    return className;
  }

  /**
   * TODO: doc
   *
   * @param anotherReference A reference to the same class
   * @return a new Reference which merges the two references
   */
  public Reference merge(Reference anotherReference) {
    if (!anotherReference.getClassName().equals(className)) {
      throw new IllegalStateException("illegal merge " + this + " != " + anotherReference);
    }
    String superName = null == this.superName ? anotherReference.superName : this.superName;
    Set<String> interfaces = new HashSet<>();
    if (null != this.interfaceNames) {
      interfaces.addAll(Arrays.asList(this.interfaceNames));
    }
    if (null != anotherReference.interfaceNames) {
      interfaces.addAll(Arrays.asList(anotherReference.interfaceNames));
    }

    return new Reference(className, superName, interfaces.toArray(new String[0]));
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
    if (loader.getResource(Utils.getResourceName(className)) != null) {
      return new ArrayList<>(0);
    } else {
      final List<Source> mismatches = new ArrayList<Source>();
      mismatches.add(new Source("TODO", 1));
      return mismatches;
    }
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
