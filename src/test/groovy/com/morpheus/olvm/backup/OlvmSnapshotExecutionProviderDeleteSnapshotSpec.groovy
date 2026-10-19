package com.morpheus.olvm.backup

import com.morpheus.olvm.OlvmCloudPlugin
import com.morpheus.olvm.util.OlvmComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class OlvmSnapshotExecutionProviderDeleteSnapshotSpec extends Specification {

	static final Map CONNECTION = [apiUrl: 'https://olvm.example.com', token: 'tok']
	static final String SNAPSHOT_BUSY = 'Cannot remove Snapshot. The VM is performing an operation on a Snapshot. Please wait for the operation to finish, and try again.'

	OlvmSnapshotExecutionProvider provider

	def setup() {
		provider = new OlvmSnapshotExecutionProvider(Mock(OlvmCloudPlugin), Mock(MorpheusContext))
		OlvmComputeUtility.metaClass.static.sleep = { Long millis -> }
		HttpApiClient.metaClass.shutdownClient = { -> }
	}

	def cleanup() {
		GroovySystem.metaClassRegistry.removeMetaClass(HttpApiClient)
		GroovySystem.metaClassRegistry.removeMetaClass(OlvmComputeUtility)
	}

	private static ServiceResponse errorResponse(String code, String reason, String detail) {
		def resp = new ServiceResponse(false, reason, null, [fault: [reason: reason, detail: detail]])
		resp.errorCode = code
		return resp
	}

	private void stubDeleteResponses(List<ServiceResponse> responses, List<String> calls) {
		HttpApiClient.metaClass.callJsonApi = { String apiUrl, String path, HttpApiClient.RequestOptions reqOptions, String method ->
			calls << "${method} ${path}".toString()
			return responses.size() > 1 ? responses.remove(0) : responses[0]
		}
	}

	def "retries the snapshot delete while oVirt reports a snapshot operation in progress"() {
		given:
		def calls = []
		stubDeleteResponses([
			errorResponse('409', 'Operation Failed', SNAPSHOT_BUSY),
			errorResponse('409', 'Operation Failed', SNAPSHOT_BUSY),
			ServiceResponse.success()
		], calls)

		when:
		def result = provider.deleteSnapshot('vm-1', 'snap-1', Mock(Cloud), CONNECTION)

		then:
		result.success
		calls == ['DELETE /ovirt-engine/api/vms/vm-1/snapshots/snap-1'] * 3
	}

	def "treats a 404 as already deleted"() {
		given:
		def calls = []
		stubDeleteResponses([errorResponse('404', 'Not Found', 'Entity not found')], calls)

		when:
		def result = provider.deleteSnapshot('vm-1', 'snap-1', Mock(Cloud), CONNECTION)

		then:
		result.success
		calls.size() == 1
	}

	def "fails immediately and reports the oVirt error on a non-409 failure"() {
		given:
		def calls = []
		stubDeleteResponses([errorResponse('400', 'Operation Failed', 'Bad request')], calls)

		when:
		def result = provider.deleteSnapshot('vm-1', 'snap-1', Mock(Cloud), CONNECTION)

		then:
		!result.success
		result.msg == 'Failed to delete snapshot snap-1: Operation Failed - Bad request'
		calls.size() == 1
	}
}
