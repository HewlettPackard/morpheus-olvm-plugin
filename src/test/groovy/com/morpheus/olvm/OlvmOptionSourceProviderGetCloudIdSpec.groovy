package com.morpheus.olvm

import spock.lang.Specification

/**
 * MORPH-17702: getCloudId blindly used args.domain.id when args.domain was not a Map, which broke
 * the cluster wizard's docker host "Configure" step. In that flow args.domain is a ComputeServer
 * (not yet part of any Cloud lookup key), so args.domain.id is the server's own id, not a cloud id.
 * loadCloud() then failed to resolve a Cloud via getCloudById() and fell back to treating the
 * ComputeServer itself as the cloud, throwing MissingPropertyException on cloud.serviceUsername.
 * getCloudId() must instead follow domain.zone.id when the domain object exposes a zone (e.g. a
 * ComputeServer), while still supporting a domain that is itself the Cloud/ComputeZone (no zone
 * property) or a plain Map.
 */
class FakeZone {
	Long id
}

class FakeServer {
	FakeZone zone
	Long id
}

class FakeCloud {
	Long id
}

class OlvmOptionSourceProviderGetCloudIdSpec extends Specification {

	def "resolves cloud id from domain.zone.id when domain is a server-like object with a zone"() {
		given:
		def server = new FakeServer(zone: new FakeZone(id: 55L), id: 999L)

		when:
		def cloudId = OlvmOptionSourceProvider.getCloudId([domain: server])

		then:
		cloudId == 55L
	}

	def "falls back to domain.id when domain has no zone property (domain is the cloud itself)"() {
		given:
		def cloud = new FakeCloud(id: 42L)

		when:
		def cloudId = OlvmOptionSourceProvider.getCloudId([domain: cloud])

		then:
		cloudId == 42L
	}

	def "prefers explicit cloudId/zoneId args over domain"() {
		expect:
		OlvmOptionSourceProvider.getCloudId([cloudId: 7L, domain: new FakeServer(zone: new FakeZone(id: 55L))]) == 7L
		OlvmOptionSourceProvider.getCloudId([zoneId: 8L, domain: new FakeServer(zone: new FakeZone(id: 55L))]) == 8L
	}

	def "supports domain passed as a Map"() {
		expect:
		OlvmOptionSourceProvider.getCloudId([domain: [cloudId: 11L]]) == 11L
		OlvmOptionSourceProvider.getCloudId([domain: [zoneId: 12L]]) == 12L
		OlvmOptionSourceProvider.getCloudId([domain: [zone: [id: 13L]]]) == 13L
	}

	/**
	 * MORPH-17702 (regression): getCloudId() alone wasn't enough. loadCloud() separately computed
	 * "def formCloud = args.zone ?: args.domain" and read formCloud.serviceUsername/servicePassword/
	 * serviceUrl off it. When args.domain is a ComputeServer (docker host configure/create wizard
	 * steps), formCloud ended up being the server itself, which has no serviceUsername property,
	 * throwing MissingPropertyException. resolveFormCloud() must resolve to the server's zone
	 * (the actual Cloud) instead of the server, while still supporting a domain that is itself the
	 * Cloud/ComputeZone or a plain Map.
	 */
	def "resolveFormCloud resolves to domain.zone when domain is a server-like object with a zone"() {
		given:
		def zone = new FakeZone(id: 55L)
		def server = new FakeServer(zone: zone, id: 999L)

		expect:
		OlvmOptionSourceProvider.resolveFormCloud(server).is(zone)
	}

	def "resolveFormCloud returns domain itself when domain has no zone property"() {
		given:
		def cloud = new FakeCloud(id: 42L)

		expect:
		OlvmOptionSourceProvider.resolveFormCloud(cloud).is(cloud)
	}

	def "resolveFormCloud returns Map domains unchanged"() {
		given:
		def domain = [serviceUsername: 'admin']

		expect:
		OlvmOptionSourceProvider.resolveFormCloud(domain).is(domain)
	}

	def "resolveFormCloud returns null for a null domain"() {
		expect:
		OlvmOptionSourceProvider.resolveFormCloud(null) == null
	}
}
