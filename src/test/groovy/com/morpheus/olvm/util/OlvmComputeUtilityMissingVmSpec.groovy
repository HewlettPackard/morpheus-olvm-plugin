package com.morpheus.olvm.util

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * MORPH-16377: when oVirt returns 404 for a VM that no longer exists, getServerDetail parsed the error body
 * as a VM and threw an NPE on vm.cpu.topology. The polling loops then ran to their full timeout instead of
 * failing fast, leaving provisioning stuck.
 */
class OlvmComputeUtilityMissingVmSpec extends Specification {

	static final Map CONNECTION = [apiUrl: 'https://olvm.example.com', token: 'abc', ignoreSSL: true]

	HttpApiClient client = Mock(HttpApiClient)

	def cleanup() {
		GroovySystem.metaClassRegistry.removeMetaClass(OlvmComputeUtility)
	}

	private void stubApiClient() {
		HttpApiClient stub = client
		OlvmComputeUtility.metaClass.static.getApiClient = { Map config -> stub }
	}

	private static ServiceResponse notFound() {
		return new ServiceResponse(success: false, errorCode: '404', data: [fault: [reason: 'Entity not found', detail: 'VM not found']])
	}

	def "getServerDetail returns a failed response with the error code without throwing when the VM is 404"() {
		given:
		stubApiClient()

		when:
		def result = OlvmComputeUtility.getServerDetail([connection: CONNECTION, serverId: 'missing-vm'])

		then:
		1 * client.callJsonApi(CONNECTION.apiUrl, '/ovirt-engine/api/vms/missing-vm', _, 'GET') >> notFound()
		0 * client.callJsonApi(*_)
		result.success == false
		result.errorCode == '404'
		result.data == null
	}

	def "checkServerReady stops polling after the first 404 instead of retrying"() {
		given:
		GroovySpy(OlvmComputeUtility, global: true)

		when:
		def result = OlvmComputeUtility.checkServerReady([connection: CONNECTION, serverId: 'missing-vm'])

		then:
		result.success != true
		1 * OlvmComputeUtility.getServerDetail(_) >> new ServiceResponse(success: false, errorCode: '404')
	}

	def "waitForServerExists fails fast instead of polling until timeout when the VM is 404"() {
		given:
		stubApiClient()
		int calls = 0
		client.callJsonApi(CONNECTION.apiUrl, '/ovirt-engine/api/vms/missing-vm', _, 'GET') >> {
			if (++calls > 1) {
				throw new IllegalStateException('kept polling after 404')
			}
			return notFound()
		}

		when:
		OlvmComputeUtility.waitForServerExists([connection: CONNECTION, server: [name: 'vm', externalId: 'missing-vm']])

		then:
		def e = thrown(RuntimeException)
		e.message.contains('not found')
	}
}
