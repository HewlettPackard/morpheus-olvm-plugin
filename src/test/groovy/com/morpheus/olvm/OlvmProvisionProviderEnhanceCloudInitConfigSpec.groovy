package com.morpheus.olvm

import spock.lang.Specification

/**
 * MORPH-18103: a VM created from a backup snapshot clones the source VM's disk verbatim,
 * including network-identity state that DHCP clients key leases on (NetworkManager's
 * per-connection lease cache, systemd-networkd's lease cache, dhclient's lease file, and
 * /etc/machine-id). Live testing confirmed the restored VM can still receive the source VM's
 * previously-leased DHCP IP even with a brand-new MAC address and even after the pre-snapshot
 * cloud-init cache reset in OlvmSnapshotExecutionProvider (which resets cloud-init's own
 * per-instance state, but not the network stack's separate lease caches). This is a
 * defense-in-depth boot-time reset of that network-stack state, applied via bootcmd (frequency
 * "always", unlike the once-per-instance write_files/runcmd) so it runs regardless of any
 * stale state carried over on the cloned disk.
 */
class OlvmProvisionProviderEnhanceCloudInitConfigSpec extends Specification {

	OlvmProvisionProvider provider = Spy(OlvmProvisionProvider, constructorArgs: [null, null])

	def dhcpNetworkConfig = [
		primaryInterface: [
			name    : 'eth0',
			doStatic: false,
			doDhcp  : true
		]
	]

	def "bootcmd section resets machine-id and DHCP lease caches for a restored instance on DHCP"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', dhcpNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'], true)
		String bootcmdSection = result.substring(result.indexOf('bootcmd:'))

		then:
		bootcmdSection.contains('rm -f /etc/machine-id')
		bootcmdSection.contains('systemd-machine-id-setup')
		bootcmdSection.contains('/var/lib/NetworkManager/*.lease')
		bootcmdSection.contains('/var/lib/dhcp/dhclient')
		bootcmdSection.contains('/run/systemd/netif/leases')
		bootcmdSection.contains('nmcli connection reload')
	}

	def "does not add a bootcmd section when isSnapshotRestore is false"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', dhcpNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'], false)

		then:
		!result.contains('bootcmd:')
		!result.contains('systemd-machine-id-setup')
	}

	def "does not add a bootcmd section when isSnapshotRestore is omitted (defaults to false)"() {
		when:
		String result = provider.enhanceCloudInitConfig('#cloud-config\n', 'myhost', 'example.com', dhcpNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'])

		then:
		!result.contains('bootcmd:')
	}

	def "merges the bootcmd reset into an existing bootcmd: section instead of duplicating it"() {
		given:
		String cloudConfigWithBootcmd = '#cloud-config\nbootcmd:\n- echo existing\n'

		when:
		String result = provider.enhanceCloudInitConfig(cloudConfigWithBootcmd, 'myhost', 'example.com', dhcpNetworkConfig, [name: 'ubuntu'], [name: 'Ubuntu 22.04'], true)

		then:
		result.count('bootcmd:') == 1
		result.contains('echo existing')
		result.contains('systemd-machine-id-setup')
	}
}
