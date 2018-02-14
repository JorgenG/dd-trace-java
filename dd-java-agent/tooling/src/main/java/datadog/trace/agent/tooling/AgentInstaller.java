package datadog.trace.agent.tooling;

import java.lang.instrument.Instrumentation;
import java.util.ServiceLoader;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderWithName;
import static datadog.trace.agent.tooling.ClassLoaderMatcher.isReflectionClassLoader;
import static net.bytebuddy.matcher.ElementMatchers.any;
import static net.bytebuddy.matcher.ElementMatchers.isBootstrapClassLoader;
import static net.bytebuddy.matcher.ElementMatchers.nameContains;
import static net.bytebuddy.matcher.ElementMatchers.nameMatches;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

@Slf4j
public class AgentInstaller {

  /**
   * Install the core bytebuddy agent along with all implementations of {@link Instrumenter}.
   *
   * @param inst Java Instrumentation used to install bytebuddy
   * @return the agent's class transformer
   */
  public static ResettableClassFileTransformer installBytebuddyAgent(final Instrumentation inst, final AgentBuilder.Listener... listeners) {
    AgentBuilder agentBuilder =
      new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .with(new LoggingListener())
        .ignore(nameStartsWith("datadog.trace."))
        .or(nameStartsWith("datadog.opentracing."))
        .or(nameStartsWith("datadog.slf4j."))
        .or(nameStartsWith("java."))
        .or(nameStartsWith("com.sun."))
        .or(nameStartsWith("sun."))
        .or(nameStartsWith("jdk."))
        .or(nameStartsWith("org.aspectj."))
        .or(nameStartsWith("org.groovy."))
        .or(nameStartsWith("com.p6spy."))
        .or(nameStartsWith("org.slf4j."))
        .or(nameContains("javassist"))
        .or(nameContains(".asm."))
        .or(nameMatches("com\\.mchange\\.v2\\.c3p0\\..*Proxy"))
        .ignore(
          any(),
          isBootstrapClassLoader()
            .or(isReflectionClassLoader())
            .or(
              classLoaderWithName(
                "org.codehaus.groovy.runtime.callsite.CallSiteClassLoader")));
    for (final AgentBuilder.Listener listener : listeners) {
      agentBuilder = agentBuilder.with(listener);
    }
    int numInstrumenters = 0;
    for (final Instrumenter instrumenter : ServiceLoader.load(Instrumenter.class)) {
      log.debug("Loading instrumentation {}", instrumenter);
      agentBuilder = instrumenter.instrument(agentBuilder);
      numInstrumenters++;
    }
    log.debug("Installed {} instrumenter(s)", numInstrumenters);

    return agentBuilder.installOn(inst);
  }

  @Slf4j
  static class LoggingListener implements AgentBuilder.Listener {

    @Override
    public void onError(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final Throwable throwable) {
      log.warn("Failed to handle " + typeName + " for transformation: " + throwable.getMessage());
      log.warn("ERROR: ", throwable);
    }

    @Override
    public void onTransformation(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded,
        final DynamicType dynamicType) {
      log.debug("Transformed {} -- {}", typeDescription, classLoader);
    }

    @Override
    public void onIgnored(
        final TypeDescription typeDescription,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}

    @Override
    public void onComplete(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}

    @Override
    public void onDiscovery(
        final String typeName,
        final ClassLoader classLoader,
        final JavaModule module,
        final boolean loaded) {}
  }

  private AgentInstaller() {}
}
