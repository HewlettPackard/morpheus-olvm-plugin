package com.morpheus.olvm

import com.morpheusdata.core.providers.ProvisionProvider
import spock.lang.Specification

class OlvmProvisionProviderMemoryStatsSpec extends Specification {

	def "guest-reported memory is not synced onto the instance max memory"() {
		given:
		ProvisionProvider provider = new OlvmProvisionProvider(null, null)

		expect:
		provider.canSyncMaxMemoryStats() == false
	}

	def "the hook is declared on the plugin API interface and overridden by the provider"() {
		expect:
		ProvisionProvider.getMethod('canSyncMaxMemoryStats') != null
		OlvmProvisionProvider.getMethod('canSyncMaxMemoryStats').declaringClass == OlvmProvisionProvider
	}
}
