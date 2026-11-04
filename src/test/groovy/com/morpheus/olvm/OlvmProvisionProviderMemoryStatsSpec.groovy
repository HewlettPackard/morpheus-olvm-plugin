package com.morpheus.olvm

import spock.lang.Specification

class OlvmProvisionProviderMemoryStatsSpec extends Specification {

	def "guest-reported memory is not synced onto the instance max memory"() {
		expect:
		new OlvmProvisionProvider(null, null).canSyncMaxMemoryStats() == false
	}
}
