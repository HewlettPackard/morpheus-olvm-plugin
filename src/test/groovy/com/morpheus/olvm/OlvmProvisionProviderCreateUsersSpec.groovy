package com.morpheus.olvm

import com.morpheusdata.model.provisioning.UserConfiguration
import com.morpheusdata.response.ProvisionResponse
import spock.lang.Specification

class OlvmProvisionProviderCreateUsersSpec extends Specification {

	OlvmProvisionProvider provider = new OlvmProvisionProvider(null, null)

	def "windows workloads return the group users for the finalize step"() {
		given:
		def users = [new UserConfiguration(username: 'jdoe', password: 'secret')]
		def response = new ProvisionResponse()

		when:
		provider.applyCreateUsers([platform: 'windows', userConfig: [createUsers: users]], response)

		then:
		response.createUsers == users
	}

	def "linux workloads leave user creation to cloud-init"() {
		given:
		def response = new ProvisionResponse()

		when:
		provider.applyCreateUsers([platform: 'linux', userConfig: [createUsers: [new UserConfiguration(username: 'jdoe')]]], response)

		then:
		response.createUsers == null
	}

	def "a missing user configuration is tolerated"() {
		given:
		def response = new ProvisionResponse()

		when:
		provider.applyCreateUsers([platform: 'windows'], response)

		then:
		response.createUsers == null
	}
}
