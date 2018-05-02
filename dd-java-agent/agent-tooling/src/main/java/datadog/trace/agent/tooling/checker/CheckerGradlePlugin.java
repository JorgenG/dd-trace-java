package datadog.trace.agent.tooling.checker;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType.Builder;

public class CheckerGradlePlugin implements Plugin {
  private static final TypeDescription InstrumenterTypeDesc =
      new TypeDescription.ForLoadedType(Instrumenter.class);

  @Override
  public boolean matches(final TypeDescription target) {
    // AutoService annotation is not retained at runtime. Check for instrumenter supertype
    boolean isInstrumenter = false;
    TypeDefinition instrumenter = target;
    while (instrumenter != null) {
      if (instrumenter.getInterfaces().contains(InstrumenterTypeDesc)) {
        isInstrumenter = true;
        break;
      }
      instrumenter = instrumenter.getSuperClass();
    }
    return isInstrumenter;
  }

  @Override
  public Builder<?> apply(Builder<?> builder, TypeDescription typeDescription) {
    return builder.visit(new CheckerVisitor());
  }
}
