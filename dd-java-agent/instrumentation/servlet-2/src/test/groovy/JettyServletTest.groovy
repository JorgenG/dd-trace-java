import datadog.opentracing.DDSpan
import datadog.opentracing.DDTracer
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.common.writer.ListWriter
import io.opentracing.util.GlobalTracer
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import spock.lang.Unroll

import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JettyServletTest extends AgentTestRunner {

  static final int PORT = TestUtils.randomOpenPort()

  // Jetty needs this to ensure consistent ordering for async.
  static CountDownLatch latch
  OkHttpClient client = new OkHttpClient.Builder()
    .addNetworkInterceptor(new Interceptor() {
    @Override
    Response intercept(Interceptor.Chain chain) throws IOException {
      def response = chain.proceed(chain.request())
      JettyServletTest.latch.await(10, TimeUnit.SECONDS) // don't block forever or test never fails.
      return response
    }
  })
  // Uncomment when debugging:
    .connectTimeout(1, TimeUnit.HOURS)
    .writeTimeout(1, TimeUnit.HOURS)
    .readTimeout(1, TimeUnit.HOURS)
    .build()

  private Server jettyServer
  private ServletContextHandler servletContext

  ListWriter writer = new ListWriter() {
    @Override
    void write(final List<DDSpan> trace) {
      add(trace)
      JettyServletTest.latch.countDown()
    }
  }
  DDTracer tracer = new DDTracer(writer)

  def setup() {
    jettyServer = new Server(PORT)
    servletContext = new ServletContextHandler()

    servletContext.addServlet(TestServlet.Sync, "/sync")

    jettyServer.setHandler(servletContext)
    jettyServer.start()

    System.out.println(
      "Jetty server: http://localhost:" + PORT + "/")

    try {
      GlobalTracer.register(tracer)
    } catch (final Exception e) {
      // Force it anyway using reflection
      final Field field = GlobalTracer.getDeclaredField("tracer")
      field.setAccessible(true)
      field.set(null, tracer)
    }
    writer.start()
    assert GlobalTracer.isRegistered()
  }

  def cleanup() {
    jettyServer.stop()
    jettyServer.destroy()
  }

  @Unroll
  def "test #path servlet call"() {
    setup:
    latch = new CountDownLatch(1)
    def request = new Request.Builder()
      .url("http://localhost:$PORT/$path")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() == expectedResponse
    writer.waitForTraces(1)
    writer.size() == 1
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().serviceName == "unnamed-java-app"
    span.context().operationName == "servlet.request"
    span.context().resourceName == "GET /$path"
    span.context().spanType == DDSpanTypes.WEB_SERVLET
    !span.context().getErrorFlag()
    span.context().parentId == 0
    span.context().tags["http.url"] == "http://localhost:$PORT/$path"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["component"] == "java-web-servlet"
    span.context().tags["http.status_code"] == null // sadly servlet 2.x doesn't expose it generically.
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
    span.context().tags.size() == 7

    where:
    path   | expectedResponse
    "sync" | "Hello Sync"
  }

  @Unroll
  def "test #path error servlet call"() {
    setup:
    def request = new Request.Builder()
      .url("http://localhost:$PORT/$path?error=true")
      .get()
      .build()
    def response = client.newCall(request).execute()

    expect:
    response.body().string().trim() != expectedResponse
    writer.waitForTraces(1)
    writer.size() == 1
    def trace = writer.firstTrace()
    trace.size() == 1
    def span = trace[0]

    span.context().operationName == "servlet.request"
    span.context().resourceName == "GET /$path"
    span.context().spanType == DDSpanTypes.WEB_SERVLET
    span.context().getErrorFlag()
    span.context().parentId == 0
    span.context().tags["http.url"] == "http://localhost:$PORT/$path"
    span.context().tags["http.method"] == "GET"
    span.context().tags["span.kind"] == "server"
    span.context().tags["component"] == "java-web-servlet"
    span.context().tags["http.status_code"] == null // sadly servlet 2.x doesn't expose it generically.
    span.context().tags["thread.name"] != null
    span.context().tags["thread.id"] != null
    span.context().tags["error"] == true
    span.context().tags["error.msg"] == "some $path error"
    span.context().tags["error.type"] == RuntimeException.getName()
    span.context().tags["error.stack"] != null
    span.context().tags.size() == 11

    where:
    path   | expectedResponse
    "sync" | "Hello Sync"
  }
}
