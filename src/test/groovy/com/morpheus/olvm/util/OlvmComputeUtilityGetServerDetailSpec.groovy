package com.morpheus.olvm.util

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * MORPH-16377: when an OLVM VM goes missing on the oVirt backend (removed out of band, or a
 * failed create), oVirt answers the VM-detail GET with a 404 (response.success == false, no
 * 'cpu'/'topology' fields in the body). getServerDetail previously parsed the response body
 * unconditionally, so it threw a NullPointerException on vm.cpu.topology.cores instead of
 * returning a clean failure. That NPE surfaced inside checkServerReady/waitForServerExists,
 * which had no terminal "missing VM" state and polled until their timeout.
 *
 * These specs verify getServerDetail fails fast and cleanly on a 404, and that
 * checkServerReady/waitForServerExists stop polling immediately instead of running to timeout.
 */
class OlvmComputeUtilityGetServerDetailSpec extends Specification {

	def cleanup() {
		GroovySystem.metaClassRegistry.removeMetaClass(HttpApiClient)
		GroovySystem.metaClassRegistry.removeMetaClass(OlvmComputeUtility)
	}

	private ServiceResponse notFoundResponse() {
		def resp = new ServiceResponse(false, 'Not Found', null, [fault: [reason: 'Not Found', detail: 'Entity not found: id=missing-vm']])
		resp.errorCode = '404'
		return resp
	}

	def "getServerDetail returns a clean failure instead of NPE-ing when oVirt returns a 404"() {
		given:
		HttpApiClient.metaClass.callJsonApi = { String apiUrl, String path, HttpApiClient.RequestOptions reqOptions, String method ->
			return notFoundResponse()
		}
		HttpApiClient.metaClass.shutdownClient = { -> }

		when:
		def result = OlvmComputeUtility.getServerDetail([connection: [apiUrl: 'https://olvm.example.com', token: 'tok'], serverId: 'missing-vm'])

		then:
		noExceptionThrown()
		result.success == false
		result.errorCode == '404'
	}

	def "checkServerReady fails fast on a 404 instead of polling until it exhausts its attempts"() {
		given:
		OlvmComputeUtility.metaClass.static.sleep = { Long millis -> }
		int callCount = 0
		OlvmComputeUtility.metaClass.static.getServerDetail = { opts ->
			callCount++
			return notFoundResponse()
		}

		when:
		def result = OlvmComputeUtility.checkServerReady([connection: [apiUrl: 'https://olvm.example.com', token: 'tok'], serverId: 'missing-vm'])

		then:
		noExceptionThrown()
		result.success == false
		callCount == 1
	}

	def "waitForServerExists fails fast instead of looping until DEFAULT_WAIT_TIMEOUT when the VM is missing"() {
		given:
		HttpApiClient.metaClass.callJsonApi = { String apiUrl, String path, HttpApiClient.RequestOptions reqOptions, String method ->
			return notFoundResponse()
		}
		HttpApiClient.metaClass.shutdownClient = { -> }
		def server = [name: 'missing-vm', externalId: 'missing-vm']

		when:
		OlvmComputeUtility.waitForServerExists([connection: [apiUrl: 'https://olvm.example.com', token: 'tok'], server: server])

		then:
		def ex = thrown(RuntimeException)
		ex.message.contains('missing-vm')
	}
}
