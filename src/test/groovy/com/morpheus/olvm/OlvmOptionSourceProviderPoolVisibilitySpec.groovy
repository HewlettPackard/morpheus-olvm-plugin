package com.morpheus.olvm

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusResourcePermissionService
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import com.morpheusdata.core.data.DataQueryResult
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * MORPH-18065: the cluster/datacenter option sources returned every pool of the cloud, so a subtenant
 * saw private clusters it was not assigned to in the Create Instance wizard. Core hides a pool from an
 * account unless the pool is public, owned by the account, or the account holds a ComputeZonePool
 * resource permission.
 */
class OlvmOptionSourceProviderPoolVisibilitySpec extends Specification {

	MorpheusContext context = Mock(MorpheusContext)
	MorpheusAsyncServices async = Mock(MorpheusAsyncServices)
	MorpheusCloudPoolService poolService = Mock(MorpheusCloudPoolService)
	MorpheusResourcePermissionService resourcePermissionService = Mock(MorpheusResourcePermissionService)
	OlvmOptionSourceProvider provider

	def setup() {
		context.getAsync() >> async
		async.getCloud() >> Mock(com.morpheusdata.core.cloud.MorpheusCloudService) { getPool() >> poolService }
		async.getResourcePermission() >> resourcePermissionService
		provider = new OlvmOptionSourceProvider(null, context)
	}

	private CloudPool pool(Long id, String visibility, Long ownerId = null) {
		new CloudPool(id: id, name: "pool-${id}", visibility: visibility, owner: ownerId ? new Account(id: ownerId) : null)
	}

	private stubPools(List<CloudPool> pools, Collection<Long> accessibleIds = []) {
		poolService.search(_) >> Single.just(new DataQueryResult(items: pools))
		resourcePermissionService.listAccessibleResources(_, _, _, _) >> Observable.fromIterable(accessibleIds)
	}

	def "subtenant only sees public, owned and permission-granted pools"() {
		given:
		def cloud = new Cloud(id: 1L, account: new Account(id: 1L))
		stubPools([pool(10L, 'public', 1L), pool(11L, 'private', 1L), pool(12L, 'private', 2L), pool(13L, 'private', 1L)], [13L])

		when:
		def result = provider.getCloudPools([accountId: 2L], 'cluster', cloud)

		then:
		result*.value == [10L, 12L, 13L]
	}

	def "cloud owner account sees every pool"() {
		given:
		def cloud = new Cloud(id: 1L, account: new Account(id: 1L))
		stubPools([pool(10L, 'public', 1L), pool(11L, 'private', 1L)])

		expect:
		provider.getCloudPools([accountId: 1L], 'cluster', cloud)*.value == [10L, 11L]
	}

	def "pools are not filtered when no account is supplied"() {
		given:
		def cloud = new Cloud(id: 1L, account: new Account(id: 1L))
		stubPools([pool(10L, 'public', 1L), pool(11L, 'private', 1L)])

		expect:
		provider.getCloudPools([:], 'cluster', cloud)*.value == [10L, 11L]
	}
}
