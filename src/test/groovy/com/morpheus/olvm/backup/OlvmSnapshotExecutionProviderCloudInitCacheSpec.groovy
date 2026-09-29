package com.morpheus.olvm.backup

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.TaskResult
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

/**
 * MORPH-18103: restoring an OLVM backup to a new instance could result in the new instance
 * reusing the source VM's cached cloud-init instance identity (network config, machine-id, etc.),
 * causing IP collisions between the source and restored VMs.
 *
 * This mirrors the established Morpheus convention (used by the VMware, KVM, and SCVMM plugins)
 * of resetting the source VM's cloud-init cache markers/machine-id immediately before snapshotting,
 * then restoring them immediately after, so the snapshot captures a "fresh" cloud-init state.
 */
class OlvmSnapshotExecutionProviderCloudInitCacheSpec extends Specification {

	OlvmSnapshotExecutionProvider provider
	MorpheusContext morpheusContext

	def setup() {
		morpheusContext = Mock(MorpheusContext)
		provider = new OlvmSnapshotExecutionProvider(null, morpheusContext)
	}

	ComputeServer buildServer(Boolean isCloudInit, String platform) {
		def image = isCloudInit == null ? null : new VirtualImage(isCloudInit: isCloudInit)
		def osType = platform == null ? null : new OsType(platform: PlatformType.valueOf(platform))
		return new ComputeServer(externalId: 'vm-1', sourceImage: image, serverOs: osType)
	}

	@Unroll
	def "isCloudInitResetEligible returns #expected when cloudInit=#isCloudInit and platform=#platform"() {
		expect:
		provider.isCloudInitResetEligible(buildServer(isCloudInit, platform)) == expected

		where:
		isCloudInit | platform  | expected
		true        | 'linux'   | true
		true        | 'windows' | false
		false       | 'linux'   | false
		null        | 'linux'   | false
	}

	def "isCloudInitResetEligible returns false when computeServer is null"() {
		expect:
		!provider.isCloudInitResetEligible(null)
	}

	def "resetCloudInitCache executes the cache/machine-id reset command on the server"() {
		given:
		def server = buildServer(true, 'linux')
		def result = Mock(TaskResult)
		1 * morpheusContext.executeCommandOnServer(server, { String cmd ->
			cmd.contains('rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg') &&
				cmd.contains('cp /etc/machine-id /tmp/machine-id-old') &&
				cmd.contains('rm -f /etc/machine-id') &&
				cmd.contains('touch /etc/machine-id')
		}) >> Single.just(result)
		provider.metaClass.sleep = { Long millis -> } // skip the real 30s flush delay in tests

		when:
		provider.resetCloudInitCache(server)

		then:
		noExceptionThrown()
	}

	def "restoreCloudInitCache executes the cache/machine-id restore command on the server"() {
		given:
		def server = buildServer(true, 'linux')
		def result = Mock(TaskResult)
		1 * morpheusContext.executeCommandOnServer(server, { String cmd ->
			cmd.contains("echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg") &&
				cmd.contains('cat /tmp/machine-id-old > /etc/machine-id') &&
				cmd.contains('rm /tmp/machine-id-old')
		}) >> Single.just(result)

		when:
		provider.restoreCloudInitCache(server)

		then:
		noExceptionThrown()
	}

	def "resetCloudInitCache swallows exceptions from the command execution"() {
		given:
		def server = buildServer(true, 'linux')
		morpheusContext.executeCommandOnServer(server, _ as String) >> { throw new RuntimeException('ssh failed') }

		when:
		provider.resetCloudInitCache(server)

		then:
		noExceptionThrown()
	}

	def "createSnapshotsForVm resets then restores the cloud-init cache around the snapshot call"() {
		given:
		def server = buildServer(true, 'linux')
		def result = Mock(TaskResult)
		def cloud = null
		provider.metaClass.sleep = { Long millis -> }
		provider.metaClass.createSnapshot = { vmId, c -> [id: 'snap-1'] }

		when:
		def rtn = provider.createSnapshotsForVm('vm-1', cloud, server)

		then:
		2 * morpheusContext.executeCommandOnServer(server, _ as String) >> Single.just(result)
		rtn.snapshot == [id: 'snap-1']
	}

	def "createSnapshotsForVm skips cloud-init cache reset for non-cloud-init servers"() {
		given:
		def server = buildServer(false, 'linux')
		provider.metaClass.createSnapshot = { vmId, c -> [id: 'snap-1'] }

		when:
		def rtn = provider.createSnapshotsForVm('vm-1', null, server)

		then:
		rtn.snapshot == [id: 'snap-1']
		0 * morpheusContext.executeCommandOnServer(*_)
	}
}
