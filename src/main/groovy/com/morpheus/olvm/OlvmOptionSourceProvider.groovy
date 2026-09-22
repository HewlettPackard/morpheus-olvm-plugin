package com.morpheus.olvm

import com.morpheus.olvm.util.OlvmComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DataQueryResult
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageType
import groovy.util.logging.Slf4j

@Slf4j
class OlvmOptionSourceProvider extends AbstractOptionSourceProvider {
    OlvmCloudPlugin plugin
    MorpheusContext morpheusContext

    public OlvmOptionSourceProvider(OlvmCloudPlugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
        this.@morpheusContext = context
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.@morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    @Override
    String getCode() {
        return 'olvm-option-source'
    }

    @Override
    String getName() {
        return 'OLVM Option Source'
    }

    @Override
    List<String> getMethodNames() {
        return new ArrayList<String>(['olvmDatacenters', 'olvmCloudDatacenters', 'olvmClusters', 'olvmTemplates', 'olvmCloudConfiguredDatacenter', 'olvmQcowImages', 'olvmHosts', 'olvmCloneTypes', 'olvmDiskProvisioningTypes', 'olvmHostAffinityTypes'])
    }

    def olvmCloneTypes(args) {
        return [
            [name: 'Linked Clone (thin, template-dependent)', value: 'linked', isDefault: true],
            [name: 'Full Clone (independent copy)', value: 'full'],
        ]
    }

    def olvmDiskProvisioningTypes(args) {
        return [
            [name: 'Thin (COW, sparse — supports snapshots)', value: 'thin', isDefault: true],
            [name: 'Preallocated (COW, full allocation)', value: 'preallocated'],
        ]
    }

    def olvmHostAffinityTypes(args) {
        return [
            [name: 'Migratable (soft preference, live migration allowed)', value: 'migratable', isDefault: true],
            [name: 'User Migratable (only manual migration allowed)', value: 'user_migratable'],
            [name: 'Pinned (hard pin, no migration)', value: 'pinned'],
        ]
    }

    def olvmHosts(args) {
        args = args instanceof Object[] ? args.getAt(0) : args
        Cloud cloud = loadCloud(args)
        def rtn = [[name: 'No preference (let OLVM decide)', value: '', isDefault: true]]

        def filters = [
            new DataFilter<String>('zone.id', cloud.id.toString()),
            new DataFilter<String>('computeServerType.code', 'olvm-hypervisor')
        ]
        // When the user has already selected a cluster, filter hosts to only that cluster's members.
        // clusterId is the Morpheus CloudPool.id, which is what olvmClusters returns as value — and
        // what HostSync stores on ComputeServer.resourcePool.id.
        if (args.clusterId) {
            filters << new DataFilter<String>('resourcePool.id', args.clusterId.toString())
        }

        def result = morpheusContext.async.computeServer.search(
            new DataQuery().withFilters(filters)
        ).blockingGet()

        for (host in result.items) {
            rtn << [name: host.name, value: host.externalId]
        }
        return rtn
    }

    def olvmQcowImages(args) {
        def rtn = []
        args = args instanceof Object[] ? args.getAt(0) : args
        def images = morpheusContext.async.virtualImage.listIdentityProjections(args?.accountId?.toLong(), ImageType.qcow2).toList().blockingGet()
        if(images) {
            if(args.phrase) {
                rtn = images.findAll{it.name.toLowerCase().contains(args.phrase.toLowerCase())}.collect { img -> [name: img.name, value: img.id] }.sort { it.name }
            } else {
                rtn = images.collect { img -> [name: img.name, value: img.id] }.sort { it.name }
            }
        }
        return rtn
    }

    def olvmTemplates(args) {
        args = args instanceof Object[] ? args.getAt(0) : args
        Cloud cloud = loadCloud(args)
        def rtn = []

        // first grab all images that have been sync'd in by this cloud
        def templates = morpheusContext.async.virtualImage.location.listIdentityProjections(
            new DataQuery().withFilters(
                new DataFilter<String>('refType', 'ComputeZone'),
                new DataFilter<String>('refId', cloud.id)
            )
        ).blockingSubscribe { imageLocation ->
            rtn << [name:imageLocation.virtualImage.name, value:imageLocation.virtualImage.id]
        }

        // then grab all qcow2 images from morpheus that have been user uploaded. These SHOULD also run in olvm
        def images = morpheusContext.async.virtualImage.search(
            new DataQuery().withFilters(
                new DataFilter<VirtualImageType>('imageType', ImageType.qcow2.toString()),
                new DataFilter<Boolean>('userUploaded', true)
            )
        ).blockingGet()

        for (image in images.items) {
            rtn << [name:image.name, value:image.id]
        }

        if(args.phrase) {
            rtn = rtn.findAll{ it.name.toLowerCase().contains(args.phrase.toLowerCase()) }.unique { it.name }.sort { it.name }
        } else {
            rtn = rtn.unique{ it.name }.sort { it.name }
        }

        return rtn
    }

    def olvmCloudConfiguredDatacenter(args) {
        Cloud cloud = loadCloud(args)
        return cloud.configMap.datacenter
    }

    def olvmCloudDatacenters(args) {
        Cloud cloud = loadCloud(args)
        def rtn
        if(cloud?.accountCredentialData?.username && cloud?.accountCredentialData?.password) {
            def connection = OlvmComputeUtility.getToken(cloud)
            if (!connection.error) {
                def dcResult = OlvmComputeUtility.listDatacenters([connection: connection])
                if (dcResult.success && dcResult.data.datacenters) {
                    rtn = [[name: morpheusContext.services.localization.get('gomorpheus.label.all'), value: 'all']]
                    for (dc in dcResult.data.datacenters) {
                        rtn << [name: dc.name, value: dc.id]
                    }
                }
            }
        }
        return rtn ?: [[name:'No datacenters found: verify credentials above.', value: '-1', isDefault: true]]
    }

    def olvmDatacenters(args) {
        def cloud = loadCloud(args)
        if (cloud.configMap.datacenter != 'all')
            return [[name:'From Cloud Config', value:'good', isDefault:true]]
        else
            return getCloudPools(args, 'datacenter')
    }
    def olvmClusters(args) {
        return getCloudPools(args, 'cluster')
    }

    protected getCloudPools(args, type, cloud = null) {
        cloud = cloud ?: loadCloud(args)
        args = args instanceof Object[] ? args.getAt(0) : args
        def rtn = []
        def firstOptionDefault = args.defaultValue ? false : true
        def pools = morpheusContext.async.cloud.pool.search(
            new DataQuery().withFilters(
                new DataFilter<String>('type', type),
                new DataFilter<String>('refType', 'ComputeZone'),
                new DataFilter<String>('refId', cloud.id)
            )
        ).blockingGet()
        def i = 0
        for (cloudPool in pools.items) {
            def setAsDefault = false
            if (firstOptionDefault && i == 0)
                setAsDefault = true
            else
                setAsDefault = args?.defaultValue == cloudPool.id.toString()
            rtn << [name:cloudPool.name, value:cloudPool.id, isDefault:setAsDefault]
            i++
        }
        return rtn
    }

    protected Cloud loadCloud(args) {
        args = args instanceof Object[] ? args.getAt(0) : args
        Long cloudId = getCloudId(args)
        Cloud rtn = cloudId ? morpheusContext.async.cloud.getCloudById(cloudId).blockingGet() : null
        def formCloud = args.zone ?: resolveFormCloud(args.domain)
        if(!rtn) {
            rtn = new Cloud()
        }

        def serviceUsername = formCloud?.serviceUsername ?: rtn.serviceUsername
        def servicePassword = (formCloud?.servicePassword && formCloud.servicePassword != '************') ? formCloud.servicePassword : rtn.servicePassword
        def serviceUrl = formCloud?.serviceUrl ?: rtn.serviceUrl

        if(args.credential == null && !serviceUsername) {
            // check for passed in credentials
            if(!rtn.accountCredentialLoaded || !rtn.accountCredentialData) {
                AccountCredential credentials = morpheusContext.services.accountCredential.loadCredentials(rtn)
                rtn.accountCredentialData = credentials?.data
            }
        } else {
            def config = [
                    username: serviceUsername,
                    password: servicePassword
            ]
            rtn.setConfigMap(rtn.getConfigMap() + config)
            rtn.accountCredentialData = morpheusContext.services.accountCredential.loadCredentialConfig(args.credential, config).data
            rtn.serviceUrl = serviceUrl
        }
        rtn.accountCredentialLoaded = true

        def proxy = args.apiProxy ? morpheusContext.async.network.networkProxy.getById(args.long('apiProxy')).blockingGet() : null
        rtn.apiProxy = proxy

        return rtn
    }

    private static getCloudId(args) {
        args = args instanceof Object[] ? args[0] : args
        def cloudId = args.cloudId ?: args.zoneId // ?: args.domain?.id
        if (!cloudId && args.domain) {
            if (args.domain instanceof Map)
                cloudId = args.domain.cloudId ?: args.domain.zoneId ?: args.domain.zone?.id
            else if (args.domain.hasProperty('zone') && args.domain.zone)
                cloudId = args.domain.zone.id
            else
                cloudId = args.domain.id
        }
        cloudId ? cloudId.toLong() : null
    }

    // args.domain may be a Cloud/ComputeZone (has cloud config directly) or a ComputeServer
    // (has a .zone association pointing at the Cloud). Only return domain itself when it
    // looks like cloud config; otherwise use its zone, to avoid accessing Cloud-only
    // properties (e.g. serviceUsername) on a ComputeServer.
    private static resolveFormCloud(domain) {
        if (!domain)
            return null
        if (domain instanceof Map)
            return domain
        if (domain.hasProperty('zone'))
            return domain.zone
        return domain
    }
}
