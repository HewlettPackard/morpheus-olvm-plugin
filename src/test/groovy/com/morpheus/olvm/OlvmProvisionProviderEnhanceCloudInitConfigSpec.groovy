package com.morpheus.olvm

import spock.lang.Specification
import spock.lang.Unroll

/**
 * MORPH-18103: restoring an OLVM backup to a new instance creates the new VM directly from
 * the backup's disk snapshot (createServerFromSnapshot). The cloned disk already contains a
 * completed cloud-init run from the source VM, so cloud-init may treat the restored VM as an
 * already-initialized instance and skip the once-per-instance write_files/runcmd modules that
 * apply the new instance's own static network config - leaving the guest configured with the
 * source instance's static IP. When isSnapshotRestore is true, enhanceCloudInitConfig must also
 * emit a bootcmd entry (frequency=always, never cached per-instance) that force-applies the same
 * network config regardless of cloud-init's cached state.
 */
class OlvmProvisionProviderEnhanceCloudInitConfigSpec extends Specification {

	OlvmProvisionProvider provider = Spy(OlvmProvisionProvider, constructorArgs: [null, null])

	def staticNetworkConfig = [
		primaryInterface: [
			name      : 'eth0',
			doStatic  : true,
			doDhcp    : false,
			ipAddress : '172.23.7.28',
			netmask   : '255.255.255.0',
			gateway   : '172.23.7.1',
			dnsServers: '8.8.8.8'
		]
	]

	@Unroll
	def "does not add a bootcmd section for static netplan config when isSnapshotRestore is #isSnapshotRestore"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', staticNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'], isSnapshotRestore)

		then:
		result.contains('write_files:')
		result.contains('/etc/netplan/99-morpheus.yaml')
		result.contains('bootcmd:') == expectBootcmd

		where:
		isSnapshotRestore | expectBootcmd
		false             | false
		null              | false
		true              | true
	}

	def "bootcmd section force-writes the same static netplan config for a restored instance"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', staticNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'], true)
		String bootcmdSection = result.substring(result.indexOf('bootcmd:'))

		then:
		bootcmdSection.contains('/etc/netplan/99-morpheus.yaml')
		bootcmdSection.contains('172.23.7.28/24')
		bootcmdSection.contains('netplan apply')
	}

	def "bootcmd section force-writes the same static NM keyfile for a restored instance on OEL"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', staticNetworkConfig, [name: 'Oracle Linux'], [name: 'OEL 9'], true)

		then:
		result.contains('bootcmd:')
		result.contains('/etc/NetworkManager/system-connections/eth0.nmconnection')
		result.contains('nmcli connection up id eth0')
	}
}
