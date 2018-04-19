package datadog.trace.agent.tooling;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

import java.security.ProtectionDomain;

public class DDMatcher {
  private static final AgentBuilder.RawMatcher NO_MATCH =
    new AgentBuilder.RawMatcher() {
      @Override
      public boolean matches(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module, Class<?> classBeingRedefined, ProtectionDomain protectionDomain) {
        return false;
      }
    };

  // TODO: document
  public static AgentBuilder.RawMatcher isSafeEnvironment() {
    return NO_MATCH;
  }
}
