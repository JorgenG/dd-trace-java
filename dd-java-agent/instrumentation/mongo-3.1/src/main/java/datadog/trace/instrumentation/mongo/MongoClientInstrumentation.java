package datadog.trace.instrumentation.mongo;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import com.mongodb.MongoClientOptions;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Modifier;
import java.util.Collections;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;

@AutoService(Instrumenter.class)
public final class MongoClientInstrumentation extends Instrumenter.Configurable {
  public static final HelperInjector MONGO_HELPER_INJECTOR =
      new HelperInjector("datadog.trace.instrumentation.mongo.DDTracingCommandListener");

  public MongoClientInstrumentation() {
    super("mongo");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            named("com.mongodb.MongoClientOptions$Builder")
                .and(
                    declaresMethod(
                        named("addCommandListener")
                            .and(
                                takesArguments(
                                    new TypeDescription.Latent(
                                        "com.mongodb.event.CommandListener",
                                        Modifier.PUBLIC,
                                        null,
                                        Collections.<TypeDescription.Generic>emptyList())))
                            .and(isPublic()))))
        .transform(MONGO_HELPER_INJECTOR)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
                    MongoClientAdvice.class.getName()))
        .asDecorator();
  }

  public static class MongoClientAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void injectTraceListener(@Advice.This final Object dis) {
      // referencing "this" in the method args causes the class to load under a transformer.
      // This bypasses the Builder instrumentation. Casting as a workaround.
      final MongoClientOptions.Builder builder = (MongoClientOptions.Builder) dis;
      final DDTracingCommandListener listener = new DDTracingCommandListener(GlobalTracer.get());
      builder.addCommandListener(listener);
    }
  }
}
