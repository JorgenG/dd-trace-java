package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.DDAdvice;
import datadog.trace.agent.tooling.DDTransformers;
import datadog.trace.agent.tooling.HelperInjector;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class FilterChain3Instrumentation extends Instrumenter.Configurable {
  public static final String SERVLET_OPERATION_NAME = "servlet.request";

  public FilterChain3Instrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(failSafe(hasSuperType(named("javax.servlet.FilterChain")))),
            classLoaderHasClasses("javax.servlet.AsyncEvent", "javax.servlet.AsyncListener"))
        .transform(
            new HelperInjector(
                "datadog.trace.instrumentation.servlet3.HttpServletRequestExtractAdapter",
                "datadog.trace.instrumentation.servlet3.HttpServletRequestExtractAdapter$MultivaluedMapFlatIterator",
                FilterChain3Advice.class.getName() + "$TagSettingAsyncListener"))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    named("doFilter")
                        .and(takesArgument(0, named("javax.servlet.ServletRequest")))
                        .and(takesArgument(1, named("javax.servlet.ServletResponse")))
                        .and(isPublic()),
                    FilterChain3Advice.class.getName()))
        .asDecorator();
  }

  public static class FilterChain3Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(@Advice.Argument(0) final ServletRequest req) {
      if (GlobalTracer.get().activeSpan() != null || !(req instanceof HttpServletRequest)) {
        // Tracing might already be applied by the FilterChain.  If so ignore this.
        return null;
      }

      final SpanContext extractedContext =
          GlobalTracer.get()
              .extract(
                  Format.Builtin.HTTP_HEADERS,
                  new HttpServletRequestExtractAdapter((HttpServletRequest) req));

      final Scope scope =
          GlobalTracer.get()
              .buildSpan(SERVLET_OPERATION_NAME)
              .asChildOf(extractedContext)
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
              .withTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET)
              .startActive(false);

      final Span span = scope.span();
      Tags.COMPONENT.set(span, "java-web-servlet");
      Tags.HTTP_METHOD.set(span, ((HttpServletRequest) req).getMethod());
      Tags.HTTP_URL.set(span, ((HttpServletRequest) req).getRequestURL().toString());
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Argument(0) final ServletRequest request,
        @Advice.Argument(1) final ServletResponse response,
        @Advice.Enter final Scope scope,
        @Advice.Thrown final Throwable throwable) {

      if (scope != null) {
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
          final HttpServletRequest req = (HttpServletRequest) request;
          final HttpServletResponse resp = (HttpServletResponse) response;
          final Span span = scope.span();

          if (throwable != null) {
            if (resp.getStatus() == HttpServletResponse.SC_OK) {
              // exception is thrown in filter chain, but status code is incorrect
              Tags.HTTP_STATUS.set(span, 500);
            }
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
            scope.close();
            scope.span().finish(); // Finish the span manually since finishSpanOnClose was false
          } else if (req.isAsyncStarted()) {
            final AtomicBoolean activated = new AtomicBoolean(false);
            // what if async is already finished? This would not be called
            req.getAsyncContext().addListener(new TagSettingAsyncListener(activated, span));
          } else {
            Tags.HTTP_STATUS.set(span, resp.getStatus());
            scope.close();
            scope.span().finish(); // Finish the span manually since finishSpanOnClose was false
          }
        }
      }
    }

    public static class TagSettingAsyncListener implements AsyncListener {
      private final AtomicBoolean activated;
      private final Span span;

      public TagSettingAsyncListener(final AtomicBoolean activated, final Span span) {
        this.activated = activated;
        this.span = span;
      }

      @Override
      public void onComplete(final AsyncEvent event) throws IOException {
        if (activated.compareAndSet(false, true)) {
          try (Scope scope = GlobalTracer.get().scopeManager().activate(span, true)) {
            Tags.HTTP_STATUS.set(
                span, ((HttpServletResponse) event.getSuppliedResponse()).getStatus());
          }
        }
      }

      @Override
      public void onTimeout(final AsyncEvent event) throws IOException {
        if (activated.compareAndSet(false, true)) {
          try (Scope scope = GlobalTracer.get().scopeManager().activate(span, true)) {
            Tags.ERROR.set(span, Boolean.TRUE);
            span.setTag("timeout", event.getAsyncContext().getTimeout());
          }
        }
      }

      @Override
      public void onError(final AsyncEvent event) throws IOException {
        if (event.getThrowable() != null && activated.compareAndSet(false, true)) {
          try (Scope scope = GlobalTracer.get().scopeManager().activate(span, true)) {
            if (((HttpServletResponse) event.getSuppliedResponse()).getStatus()
                == HttpServletResponse.SC_OK) {
              // exception is thrown in filter chain, but status code is incorrect
              Tags.HTTP_STATUS.set(span, 500);
            }
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(Collections.singletonMap(ERROR_OBJECT, event.getThrowable()));
          }
        }
      }

      @Override
      public void onStartAsync(final AsyncEvent event) throws IOException {}
    }
  }
}
