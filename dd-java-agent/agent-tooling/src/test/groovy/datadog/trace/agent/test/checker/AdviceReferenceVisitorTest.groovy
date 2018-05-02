package datadog.trace.agent.test.checker

import datadog.trace.agent.test.AdviceClass
import datadog.trace.agent.tooling.checker.AdviceReferenceVisitor
import spock.lang.Specification


class AdviceReferenceVisitorTest extends Specification {

  def "method and field references"() {
    setup:
    Map<String, Reference> references = AdviceReferenceVisitor.createReferencesFrom(AdviceClass.getName(), this.getClass().getClassLoader())
    // TODO: classes from field
    // TODO: access checking for field and method references

    expect:
    references.keySet().size() == 6
  }
}
