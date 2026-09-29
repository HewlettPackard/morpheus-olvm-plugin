package com.morpheus.olvm

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheus.olvm.backup.OlvmSnapshotBackupProvider
import com.morpheus.olvm.util.OlvmComputeUtility
import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.VmProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeTypeSet
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.VirtualImageType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.model.provisioning.NetworkConfiguration
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class OlvmProvisionProvider extends AbstractProvisionProvider implements VmProvisionProvider, WorkloadProvisionProvider, WorkloadProvisionProvider.ResizeFacet {
	public static final String PROVISION_PROVIDER_CODE = 'olvm-provision-standard'
	protected static final IMAGE_TIMEOUT = 60l * 60l * 1000l // one hour
	protected static final IMAGE_TTL = 60l * 60l * 1000l // one hour
	protected static final DEFAULT_MIN_DISK = 5
	protected static final DEFAULT_MIN_RAM = 512 * ComputeUtility.ONE_MEGABYTE

	protected MorpheusContext context
	protected Plugin plugin

	public OlvmProvisionProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.@context = ctx
		this.@plugin = plugin
	}

	/**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		OlvmVersion.setMDC()
		log.debug("prepareWorkload: workload=${workload?.id}, server=${workload?.server?.id}, platform=${workload?.server?.platform}")
		ServiceResponse<PrepareWorkloadResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareWorkloadResponse(workload: workload, options: [sendIp: false])
		ComputeServer server = workload.server

		if(server.platform == "linux") {
			resp.data.disableCloudInit = false
			resp.data.disableAutoUpdates = false
		}

		// lets figure out what image we are deploying
		 Map connection = OlvmComputeUtility.getToken(server.cloud, morpheus)
		 log.debug("prepareWorkload: connection acquired: ${connection != null}, apiUrl=${connection?.apiUrl}")
		 def imageType = workload.getConfigMap().imageType ?: 'default'
		 log.debug("prepareWorkload: imageType=${imageType}")
		 def virtualImage = getWorkloadImage(workload, opts)
		 log.debug("prepareWorkload: virtualImage=${virtualImage?.id} (${virtualImage?.name}), imageType=${virtualImage?.imageType}")
		 def config = workload.configMap
		 if (virtualImage) {
			 //this ensures the image is set correctly for provisioning as it enters runWorkload
			 workload.server.sourceImage = virtualImage
			 VirtualImageLocation location = ensureVirtualImageLocation(connection, virtualImage, server.cloud)
			 log.debug("prepareWorkload: virtualImageLocation=${location?.id}, externalId=${location?.externalId}")
			 resp.data.setVirtualImageLocation(location)

			 if (virtualImage.osType?.name?.contains('ubuntu') && MorpheusUtils.compareVersions(virtualImage.osType?.osVersion, '16.04') >= 0) {
				 resp.data.disableAutoUpdates = true
			 }
			 resp.success = true
		 }
		 else {
			 log.warn("prepareWorkload: Virtual Image not found for workload=${workload?.id}, config=${workload?.getConfigMap()}")
			 resp.success = false
			 resp.msg = "Virtual Image not found"
		 }

		log.debug("prepareWorkload: returning success=${resp.success}, msg=${resp.msg}")
		return resp
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers {@link CloudProvider#getDefaultProvisionTypeCode()} method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'olvm-symbolonly.svg', darkPath:'olvm-symbolonly.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType([
			name:'datacenter',
			code:'olvm.plugin.provision.datacenter',
			category:'provisionType.olvm',
			fieldName:'datacenterId',
			fieldContext:'config',
			fieldLabel:'Datacenter',
			required:true,
			inputType:OptionType.InputType.SELECT,
			displayOrder:100,
			optionSource:'olvmDatacenters',
			noBlank:true,
			visibleOnCode:'config.cloudDatacenterId:all'
		])
		options << new OptionType([
			name:'cloudDatacenter',
			code:'olvm.plugin.provision.cloudDatacenter',
			category:'provisionType.olvm',
			fieldName:'cloudDatacenterId',
			fieldContext:'config',
			fieldLabel:'Datacenter',
			fieldClass:'option-source-hidden',
			inputType:OptionType.InputType.HIDDEN,
			displayOrder:1000,
			optionSource:'olvmCloudConfiguredDatacenter'
		])
		options << new OptionType([
			name:'cluster',
			code:'olvm.plugin.provision.cluster',
			category:'provisionType.olvm',
			fieldName:'clusterId',
			fieldContext:'config',
			fieldLabel:'Cluster',
			required:true,
			noBlank:true,
			inputType:OptionType.InputType.SELECT,
			displayOrder:110,
			optionSource:'olvmClusters'
		])
		options << new OptionType([
			name:'skip agent install',
			code: 'olvm.plugin.provision.noAgent',
			category:'provisionType.olvm',
			fieldName: 'noAgent',
			fieldCode: 'gomorpheus.optiontype.SkipAgentInstall',
			fieldLabel: 'Skip Agent Install',
			fieldContext: 'config',
			fieldGroup: "Advanced Options",
			required: false,
			enabled: true,
			editable: false,
			global: false,
			displayOrder: 104,
			inputType: OptionType.InputType.CHECKBOX,
			helpBlock: 'Skipping Agent installation will result in a lack of logging and guest operating system statistics. Automation scripts may also be adversely affected.'
		])
		options << new OptionType([
			name: 'cloneType',
			code: 'olvm.plugin.provision.cloneType',
			category: 'provisionType.olvm',
			fieldName: 'cloneType',
			fieldContext: 'config',
			fieldLabel: 'Clone Type',
			fieldGroup: 'Advanced Options',
			inputType: OptionType.InputType.SELECT,
			displayOrder: 120,
			defaultValue: 'linked',
			optionSource: 'olvmCloneTypes',
			displayValueOnDetails: true,
			helpBlock: 'Linked clone shares template storage and is faster to create, but the template cannot be deleted while linked VMs exist. Full clone is fully independent but uses more storage and takes longer to provision.'
		])
		options << new OptionType([
			name: 'diskProvisioning',
			code: 'olvm.plugin.provision.diskProvisioning',
			category: 'provisionType.olvm',
			fieldName: 'diskProvisioning',
			fieldContext: 'config',
			fieldLabel: 'Disk Provisioning',
			fieldGroup: 'Advanced Options',
			inputType: OptionType.InputType.SELECT,
			displayOrder: 121,
			defaultValue: 'thin',
			optionSource: 'olvmDiskProvisioningTypes',
			displayValueOnDetails: true,
			helpBlock: 'Thin: only used blocks are allocated on disk; supports snapshots and stateless mode. Preallocated: all blocks are allocated at creation for more predictable storage performance.'
		])
		options << new OptionType([
			name: 'stateless',
			code: 'olvm.plugin.provision.stateless',
			category: 'provisionType.olvm',
			fieldName: 'stateless',
			fieldContext: 'config',
			fieldLabel: 'Stateless VM',
			fieldGroup: 'Advanced Options',
			inputType: OptionType.InputType.CHECKBOX,
			displayOrder: 122,
			displayValueOnDetails: true,
			helpBlock: 'When enabled, VM disk state is automatically rolled back to its pre-start snapshot on every shutdown. Requires thin (COW) disk provisioning.'
		])
		options << new OptionType([
			name: 'host',
			code: 'olvm.plugin.provision.hostId',
			category: 'provisionType.olvm',
			fieldName: 'hostId',
			fieldContext: 'config',
			fieldLabel: 'Host',
			fieldGroup: 'Advanced Options',
			inputType: OptionType.InputType.SELECT,
			displayOrder: 130,
			required: false,
			optionSource: 'olvmHosts',
			dependsOn: 'olvm.plugin.provision.cluster',
			displayValueOnDetails: true,
			helpBlock: 'Optionally target a specific hypervisor host within the selected cluster. Leave blank to let OLVM choose.'
		])
		options << new OptionType([
			name: 'hostAffinity',
			code: 'olvm.plugin.provision.hostAffinity',
			category: 'provisionType.olvm',
			fieldName: 'hostAffinity',
			fieldContext: 'config',
			fieldLabel: 'Host Affinity',
			fieldGroup: 'Advanced Options',
			inputType: OptionType.InputType.SELECT,
			displayOrder: 131,
			defaultValue: 'migratable',
			optionSource: 'olvmHostAffinityTypes',
			displayValueOnDetails: true,
			helpBlock: 'Controls migration behaviour when a host is selected. Migratable: prefer the host but allow live migration. User Migratable: only administrator-initiated migration allowed. Pinned: VM is locked to the selected host with no migration possible.'
		])
		return options
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptions = []
		nodeOptions << new OptionType([
			name:'image',
			code:'olvm-node-image',
			fieldName:'virtualImage.id',
			fieldContext:'domain',
			fieldLabel:'VM Image',
			inputType:OptionType.InputType.TYPEAHEAD,
			displayOrder:100,
			required:false,
			optionSource:'olvmQcowImages'
		])
		nodeOptions << new OptionType([
			name:'osType',
			code:'olvm-node-osType',
			fieldName:'osType.id',
			fieldCode: 'gomorpheus.label.osType',
			fieldContext:'domain',
			fieldLabel:'OsType',
			inputType:OptionType.InputType.SELECT,
			displayOrder:99,
			required:false,
			optionSource:'osTypes'
		])
		nodeOptions << new OptionType([
			name:'mountLogs',
			code:'olvm-node-log-folder',
			fieldName:'mountLogs',
			fieldContext:'domain',
			fieldLabel:'Log Folder',
			inputType:OptionType.InputType.TEXT,
			displayOrder:101,
			required:false
		])
		nodeOptions << new OptionType([
			name:'mountConfig',
			code:'olvm-node-config-folder',
			fieldName:'mountConfig',
			fieldContext:'domain',
			fieldLabel:'Config Folder',
			inputType:OptionType.InputType.TEXT,
			displayOrder:102,
			required:false
		])
		nodeOptions << new OptionType([
			name:'mountData',
			code:'olvm-node-deploy-folder',
			fieldName:'mountData',
			fieldContext:'domain',
			fieldLabel:'Deploy Folder',
			inputType:OptionType.InputType.TEXT,
			displayOrder:103,
			helpText:'(Optional) If using deployment services, this mount point will be replaced with the contents of said deployments.',
			required:false
		])
		nodeOptions << new OptionType([
			name:'checkTypeCode',
			code:'olvm-node-check-type-code',
			fieldName:'checkTypeCode',
			fieldContext:'domain',
			fieldLabel:'CheckType',
			inputType:OptionType.InputType.HIDDEN,
			defaultValue:'vmCheck',
			displayOrder:104,
			required:false
		])
		nodeOptions << new OptionType([
			name:'statTypeCode',
			code:'olvm-node-stat-type-code',
			fieldName:'statTypeCode',
			fieldContext:'domain',
			fieldLabel:'statTypeCode',
			inputType:OptionType.InputType.HIDDEN,
			defaultValue:'vm',
			displayOrder:104,
			required:false
		])
		nodeOptions << new OptionType([
			name:'logTypeCode',
			code:'olvm-node-log-type-code',
			fieldName:'logTypeCode',
			fieldContext:'domain',
			fieldLabel:'logTypeCode',
			inputType:OptionType.InputType.HIDDEN,
			defaultValue:'vm',
			displayOrder:104,
			required:false
		])
		nodeOptions << new OptionType([
			name:'serverType',
			code:'olvm-node-server-type',
			fieldName:'serverType',
			fieldContext:'domain',
			fieldLabel:'serverType',
			inputType:OptionType.InputType.HIDDEN,
			defaultValue:'vm',
			displayOrder:105,
			required:false
		])
		nodeOptions << new OptionType([
			name:'showServerLogs',
			code:'olvm-node-show-server-logs',
			fieldName:'showServerLogs',
			fieldContext:'domain',
			fieldLabel:'showServerLogs',
			inputType:OptionType.InputType.HIDDEN,
			defaultValue:'true',
			displayOrder:105,
			required:false
		])
		return nodeOptions
	}

	@Override
	// images can be uploaded in raw, qcow2, vhdx, vdi, or vmdk formats and will be converted to qcow2
	Collection<VirtualImageType> getVirtualImageTypes() {
		return [new VirtualImageType(code: 'qcow2', name: 'QCOW2')]
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		return getStorageVolumeTypes()
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		return getStorageVolumeTypes()
	}

	/**
	 * The name of the deployment service for this provider
	 * valid options include: vmDeployTargetService, dockerDeployTargetService, kubernetesDeployTargetService, and cloudFoundryDeployTargetService
	 * @return String
	 */
	@Override
	String getDeployTargetService() {
		return 'vmDeployTargetService'
	}

	private getStorageVolumeTypes() {
		Collection<StorageVolumeType> volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code        : 'olvm-standard', displayName: 'standard', name: 'standard',
			description : 'OLVM standard volume', volumeType: 'volume', enabled: true,
			customLabel : true, customSize: true, defaultType: true, autoDelete: true,
			minStorage  : (ComputeUtility.ONE_GIGABYTE), maxStorage: (16L * ComputeUtility.ONE_TERABYTE),
			hasDatastore: true, allowSearch: true, volumeCategory: 'volume',
			displayOrder: 0
		])
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		Collection<ServicePlan> plans = []
		plans << new ServicePlan(
			code:'olvm.plugin.512',
			editable:true,
			name:'1 CPU, 512MB Memory',
			description:'1 CPU, 512MB Memory',
			sortOrder:0,
			maxStorage:10l * 1024l * 1024l * 1024l,
			maxMemory:1l * 512l * 1024l * 1024l, maxCpu:0,
			maxCores:1,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.1024',
			editable:true,
			name:'1 CPU, 1GB Memory',
			description:'1 CPU, 1GB Memory',
			sortOrder:1,
			maxStorage:10l * 1024l * 1024l * 1024l,
			maxMemory:1l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:1,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.2048',
			editable:true,
			name:'1 CPU, 2GB Memory',
			description:'1 CPU, 2GB Memory',
			sortOrder:2,
			maxStorage:20l * 1024l * 1024l * 1024l,
			maxMemory:2l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:1,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.4096',
			editable:true,
			name:'1 CPU, 4GB Memory',
			description:'1 CPU, 4GB Memory',
			sortOrder:3,
			maxStorage:40l * 1024l * 1024l * 1024l,
			maxMemory:4l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:1,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.8192',
			editable:true,
			name:'2 CPU, 8GB Memory',
			description:'2 CPU, 8GB Memory',
			sortOrder:4,
			maxStorage:80l * 1024l * 1024l * 1024l,
			maxMemory:8l * 1024l * 1024l * 1024l,
			maxCpu:0, maxCores:2,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.16384',
			editable:true,
			name:'2 CPU, 16GB Memory',
			description:'2 CPU, 16GB Memory',
			sortOrder:5,
			maxStorage:160l * 1024l * 1024l * 1024l,
			maxMemory:16l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:2,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.24576',
			editable:true,
			name:'4 CPU, 24GB Memory',
			description:'4 CPU, 24GB Memory',
			sortOrder:6,
			maxStorage:240l * 1024l * 1024l * 1024l,
			maxMemory:24l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:4,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.32768',
			editable:true,
			name:'4 CPU, 32GB Memory',
			description:'4 CPU, 32GB Memory',
			sortOrder:7,
			maxStorage:320l * 1024l * 1024l * 1024l,
			maxMemory:32l * 1024l * 1024l * 1024l,
			maxCpu:0,
			maxCores:4,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			coresPerSocket:1
		)
		plans << new ServicePlan(
			code:'olvm.plugin.custom',
			editable:true,
			name:'Custom OLVM',
			description:'Custom OLVM',
			sortOrder:20,
			customMaxStorage:true,
			customMaxDataStorage:true,
			addVolumes:true,
			customCpu:true,
			customCores:true,
			customMaxMemory:true,
			deletable:false,
			provisionable:true,
			maxStorage:0l,
			maxMemory:0l,
			maxCpu:0,
			maxCores:1,
			coresPerSocket:0
		)
		return plans
	}

	@Override
	Boolean lvmSupported() {
		return true
	}

	@Override
	Boolean multiTenant() {
		return false
	}

	@Override
	Boolean aclEnabled() {
		return false
	}

	@Override
	String getHostDiskMode() {
		return "lvm"
	}

	@Override
	Boolean hasSecurityGroups() {
		return false
	}

	@Override
	Boolean supportsAutoDatastore() {
		return false
	}

	@Override
	String getNodeFormat() {
		return 'vm'
	}

	@Override
	ServiceResponse validateHost(ComputeServer computeServer, Map map) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "prepareHost: ${server} ${hostRequest} ${opts}"

		Cloud cloud = server.cloud
		ServiceResponse<PrepareHostResponse> resp = new ServiceResponse<>()
		resp.data = new PrepareHostResponse(computeServer: server, disableCloudInit: false, options: [sendIp: false])

		Map connection = OlvmComputeUtility.getToken(cloud, morpheus)

		try {
			def layout = server?.layout
			def typeSet = server.typeSet
			def config = server.getConfigMap()
			def imageType = config.templateTypeSelect ?: 'default'
			VirtualImage virtualImage

			if(server.sourceImage){
				virtualImage = server.sourceImage
				ensureVirtualImageLocation(connection, virtualImage, cloud)
			} else if(layout && typeSet) {
				Long computeTypeSetId = server.typeSet?.id
				if(computeTypeSetId) {
					ComputeTypeSet computeTypeSet = morpheus.services.computeTypeSet.get(computeTypeSetId)
					WorkloadType workloadType = computeTypeSet.getWorkloadType()

					if(workloadType) {
						Long workloadTypeId = workloadType.Id
						WorkloadType containerType = morpheus.services.containerType.get(workloadTypeId)
						Long virtualImageId = containerType.virtualImage.id
						virtualImage = morpheus.services.virtualImage.get(virtualImageId)
						if(virtualImage) {
							ensureVirtualImageLocation(connection, virtualImage, cloud)
						}
					}
				}
			} else if(imageType == 'custom') {
				if (config.imageId) {
					Long imageId = config.imageId?.toLong()
					virtualImage = morpheus.services.virtualImage.get(imageId)
					if(virtualImage) {
						ensureVirtualImageLocation(connection, virtualImage, cloud)
					}
				}
			} else {
				virtualImage = morpheus.services.virtualImage.find(new DataQuery().withFilter("code", "olvm.plugin.image.morpheus.ubuntu.20.04.amd64"))
				if(virtualImage) {
					ensureVirtualImageLocation(connection, virtualImage, cloud)
				}
			}
			if(!virtualImage) {
				resp.msg = "No virtual image selected"
			} else {
				server.sourceImage = virtualImage
				saveAndGet(server)
				resp.success = true
			}
		} catch(e) {
			resp.msg = "Error in prepareHost: ${e}"
			log.error("${resp.msg}, ${e}", e)

		}
		return resp
	}

	@Override
	ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
		log.debug "runHost: ${server} ${hostRequest} ${opts}"
		Map connection
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true, installAgent: false)
		try {
			Cloud cloud = server.cloud
			connection = OlvmComputeUtility.getToken(cloud, morpheus)
			VirtualImage virtualImage = server.sourceImage

			def runConfig = buildHostRunConfig(server, hostRequest, virtualImage, connection, opts)
			runVirtualMachine(cloud, hostRequest, runConfig, provisionResponse, opts + [connection:connection])

			if (provisionResponse.success != true) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'vm config error', error: provisionResponse.message, data: provisionResponse)
			} else {
				return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
			}

		}
		catch (Throwable t) {
			log.error "runHost: ${t}", t
			provisionResponse.setError(t.message)
			return new ServiceResponse(success: false, msg: t.message, error: t.message, data: provisionResponse)
		}
	}

	@Override
	ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server) {
		log.debug("waitForHost: ${server}")
		try {
			return getServerDetails(server)
		} catch(e) {
			log.error("Error waitForHost: ${e}", e)
			return new ServiceResponse(success: false, msg: "Error in waiting for Host: ${e}")
		}
	}

	@Override
	ServiceResponse finalizeHost(ComputeServer server) {
		ServiceResponse rtn = ServiceResponse.prepare()
		log.debug("finalizeHost: ${server?.id}")
		try {
			def serverDetails = getServerDetails(server)

			// Prefer IP reported by OLVM guest agent.
			// Fall back to server.sshHost — the Morpheus agent sets this when it calls home,
			// so it always holds the live DHCP address even when no guest agent is installed.
			def privateIp = serverDetails.data?.privateIp ?: server.sshHost
			def publicIp  = serverDetails.data?.publicIp  ?: server.sshHost
			log.debug("finalizeHost: privateIp=${privateIp} (olvmIp=${serverDetails.data?.privateIp}, sshHost=${server.sshHost})")

			if (privateIp) {
				def doSave = false
				if (server.internalIp != privateIp) {
					server.internalIp = privateIp
					doSave = true
				}
				if (server.externalIp != publicIp) {
					server.externalIp = publicIp
					doSave = true
				}
				if (doSave) {
					morpheus.async.computeServer.bulkSave([server]).blockingGet()
				}
			}
			rtn.success = serverDetails.success
		} catch(e) {
			rtn.success = false
			rtn.msg = "Error in finalizing server: ${e.message}"
			log.error("Error in finalizeHost: {}", e, e)
		}

		return rtn
	}

	/**
	 * Determines if this provision type has datastores that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has datastores
	 */
	@Override
	Boolean hasDatastores() {
		return true
	}

	/**
	 * Determines if this provision type has networks that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has networks
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/**
	 * Determines if this provision type supports service plans that expose the tag match property.
	 * @return Boolean representation of whether or not service plans expose the tag match property.
	 */
	@Override
	Boolean hasPlanTagMatch() {
		return false
	}

	/**
	 * Returns the maximum number of network interfaces that can be chosen when provisioning with this type
	 * @return maximum number of networks or 0,null if unlimited.
	 */
	@Override
	Integer getMaxNetworks() {
		return 0
	}

	/**
	 * Determines if this provision type has resources pools that can be selected or not.
	 * @return Boolean representation of whether or not this provision type has resource pools
	 */
	@Override
	Boolean hasComputeZonePools() {
		false
	}

	/**
	 * Indicates if Network selection should be scoped to the ComputeZonePool selected during provisioning
	 * @return Boolean
	 */
	Boolean networksScopedToPools() {
		return false
	}

	/**
	 * Indicates if a ComputeZonePool is required during provisioning
	 * @return Boolean
	 */
	@Override
	Boolean computeZonePoolRequired() {
		return false
	}

	/**
	 * Determines if this provision type allows the rot volume to be renamed.
	 * @return Boolean representation of whether or not this provision type allows the rot volume to be renamed
	 */
	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	/**
	 * Determines if this provision type allows the root volume to be resized.
	 * @return Boolean representation of whether or not this provision type allows the root volume to be resized
	 */
	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	/**
	 * Indicates if volumes may be added during provisioning
	 * @return Boolean
	 */
	@Override
	Boolean canAddVolumes() {
		return true
	}

	/**
	 * Determines if this provision type allows the user to add data volumes.
	 * @return Boolean representation of whether or not this provision type allows the user to add data volumes
	 */
	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	@Override
	Boolean customSupported() {
		return true
	}

	@Override
	Boolean createDefaultInstanceType() {
		return true
	}

	@Override
	String getDefaultInstanceTypeDescription() {
		return "This instance type will allow you to select any OLVM image for deployment"
	}

	/**
	* Returns the host type that is to be provisioned
	* @return HostType
	*/
	@Override
	HostType getHostType() {
		HostType.vm
	}

	@Override
	ArrayList<OptionType> getDefaultInstanceTypeOptions() {
		Collection<OptionType> options = []
		options << new OptionType([
			name:'template',
			code:'olvm.plugin.provision.template',
			category:'provisionType.olvm',
			fieldName:'template',
			fieldContext:'config',
			fieldLabel:'Template',
			required:true,
			noBlank:true,
			inputType:OptionType.InputType.TYPEAHEAD,
			displayOrder:110,
			optionSource:'olvmTemplates'
		])
	}

	/**
	 * Custom service plans can be created for this provider
	 * @return Boolean
	 */
	Boolean supportsCustomServicePlans() {
		return true;
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.debug("validateWorkload: ${opts}")
		ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
		try {
			def validateTemplate = opts.template != null
			def validationResults = OlvmComputeUtility.validateServerConfig(morpheus, [validateTemplate:validateTemplate] + opts)
			if(!validationResults.success) {
				validationResults.errors?.each { it ->
					rtn.addError(it.field, it.msg)
				}
			}
		} catch(e) {
			log.error("validateWorkload error: ${e}", e)
		}
		return rtn
	}

	/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		OlvmVersion.setMDC()
		log.debug "runWorkload: workload=${workload?.id}, server=${workload?.server?.id}"
		Map connection = opts.connection
		ProvisionResponse provisionResponse = new ProvisionResponse(success: true)
		ComputeServer server = workload.server
		try {
			Cloud cloud = server.cloud
			if (!connection) {
				log.debug("runWorkload: acquiring connection for cloud=${cloud?.id}")
				connection = OlvmComputeUtility.getToken(cloud, morpheus)
				log.debug("runWorkload: connection acquired: ${connection != null}, apiUrl=${connection?.apiUrl}")
			}
			VirtualImage virtualImage = server.sourceImage
			log.debug("runWorkload: virtualImage=${virtualImage?.id} (${virtualImage?.name}), server.platform=${server.platform}, agentMode=${cloud.agentMode}")
			def runConfig = buildWorkloadRunConfig(workload, workloadRequest, virtualImage, connection, opts)
			log.debug("runWorkload: runConfig built - name=${runConfig.name}, imageRef=${runConfig.imageRef}, clusterRef=${runConfig.clusterRef}, datacenterRef=${runConfig.datacenterRef}, noAgent=${runConfig.noAgent}, installAgent=${runConfig.installAgent}")
			runVirtualMachine(cloud, workloadRequest, runConfig, provisionResponse, opts + [connection:connection])
			log.debug("runWorkload: runVirtualMachine complete - provisionResponse.success=${provisionResponse.success}, externalId=${provisionResponse.externalId}")
			log.info("Checking Server Interfaces....")
			workload.server.interfaces?.each { netInt ->
				log.info("Net Interface: ${netInt.id} -> Network: ${netInt.network?.id}")
			}
			provisionResponse.noAgent = opts.noAgent ?: false
			if (!provisionResponse.success) {
				return new ServiceResponse(success: false, msg: provisionResponse.message ?: 'VM provisioning failed', data: provisionResponse)
			}
			return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)
		}
		catch (Throwable t) {
			log.error("runWorkload error: ${t.message}", t)
			provisionResponse.setError(t.message)
			return new ServiceResponse(success: false, msg:t.message, error:t.message, data: provisionResponse)
		}
	}

	/**
	 * Request to scale the size of the Workload. Most likely, the implementation will follow that of resizeServer
	 * as the Workload usually references a ComputeServer. It is up to implementations to create the volumes, set the memory, etc
	 * on the underlying ComputeServer in the cloud environment. In addition, implementations of this method should
	 * add, remove, and update the StorageVolumes, StorageControllers, ComputeServerInterface in the cloud environment with the requested attributes
	 * and then save these attributes on the models in Morpheus. This requires adding, removing, and saving the various
	 * models to the ComputeServer using the appropriate contexts. The ServicePlan, memory, cores, coresPerSocket, maxStorage values
	 * defined on ResizeRequest will be set on the Workload and ComputeServer upon return of a successful ServiceResponse
	 * @param instance to resize
	 * @param workload to resize
	 * @param resizeRequest the resize requested parameters
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
		def server = morpheus.async.computeServer.get(workload.server.id).blockingGet()
		if(server) {
			return internalResizeServer(server, resizeRequest, opts)
		} else {
			return ServiceResponse.error("No server provided")
		}
	}

	private ServiceResponse internalResizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
		def rtn = [success:false, supported:true]
		Map connection = opts.connection
		Cloud cloud = server.cloud
		ServicePlan plan = resizeRequest.plan
		HttpApiClient client
		try {
			if (!connection) {
				connection = OlvmComputeUtility.getToken(cloud, morpheus)
			}
			def serverId = server.id
			client = OlvmComputeUtility.getApiClient(connection)
			def headers = OlvmComputeUtility.getAuthenticatedBaseHeaders(connection)
			def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:true)

			def statusResults = OlvmComputeUtility.waitForSomeStuffToHappen([label:"Waiting for vm to stop"]) {
				def v = client.callJsonApi(
					connection.apiUrl,
					"/ovirt-engine/api/vms/${server.externalId}".toString(),
					reqOptions,
					'GET'
				).data
				return v.status == 'down'
			}

			if(statusResults.success == true) {
				def saveVmProperties = false
				def memory = server.maxMemory
				def cores = server.maxCores
				def coresPerSocket = server.coresPerSocket

				//instance size
				if (plan?.id != server.plan?.id && !plan.customMaxMemory) {
					server.plan = plan
					memory = plan.maxMemory
					cores = plan.maxCores
					coresPerSocket = plan.coresPerSocket
					saveVmProperties = true
				}
				else {
					if (plan.id != server.plan.id) {
						server.plan = plan
						saveVmProperties = true
					}
					memory = resizeRequest.maxMemory
					cores = resizeRequest.maxCores
					coresPerSocket = opts.servicePlanOptions?.maxCores.toLong() ?: resizeRequest.coresPerSocket

					if (memory != server.maxMemory || cores != server.maxCores || coresPerSocket != server.coresPerSocket)
						saveVmProperties = true
				}

				if (saveVmProperties) {
					log.info("Resizing Plan")
					OlvmComputeUtility.updateVmProperties([connection:connection, vm:[id:server.externalId, memory:memory, maxCores:cores, coresPerSocket:coresPerSocket]])
					server.maxMemory = memory
					server.maxCores = cores * coresPerSocket
					server.coresPerSocket = coresPerSocket
					server.setConfigProperty('maxMemory', memory)
					server = saveAndGet(server)
				}

				//disk sizes
				def maxStorage = resizeRequest.maxStorage
				def newCounter = server.volumes?.size()
				def volumeType = morpheus.async.storageVolume.storageVolumeType.find(
					 new DataQuery().withFilters(new DataFilter<String>('code', 'olvm-standard'))
				).blockingGet()

				resizeRequest.volumesAdd?.each {newVolumeProps ->
					log.info("Adding New Volume")
					def deviceName = OlvmComputeUtility.getDeviceName(newCounter)

					// have to grab externalId for the selected datastore
					def datastore = morpheus.async.cloud.datastore.get(newVolumeProps.datastoreId.toLong()).blockingGet()
					def addDiskResult = OlvmComputeUtility.addDiskToVm([
						connection:connection, vmId:server.externalId,
						disk:[
							maxStorage:newVolumeProps.maxStorage, name:newVolumeProps.name, datastore:[externalId:datastore.externalId],
							deviceName:deviceName
						]
					])

					def newDisk = addDiskResult.data

					def newVolume = new StorageVolume(
						refType: 'ComputeZone',
						refId: cloud.id,
						account: server.account,
						maxStorage: newVolumeProps.maxStorage,
						type: volumeType,
						externalId:newDisk.externalId,
						deviceName:deviceName,
						deviceDisplayName:deviceName,
						datastore:datastore,
						name: newVolumeProps.name,
						displayOrder: newCounter,
						status: 'provisioned',
						rootVolume:false,
						removable:true
					)
					log.info("Saving Volume")
					morpheus.async.storageVolume.create([newVolume], server).blockingGet()
					server = morpheus.async.computeServer.get(server.id).blockingGet()
					newCounter++
				}

				resizeRequest.volumesUpdate?.each { volumeUpdate ->
					log.info("resizing vm storage: count: ${volumeUpdate}")
					StorageVolume existing = volumeUpdate.existingModel
					Map updateProps = volumeUpdate.updateProps
					if (existing) {
						//existing disk - resize it
						if (updateProps.maxStorage > existing.maxStorage) {
							def volumeId = existing.externalId
							def resizeResults = OlvmComputeUtility.updateDiskSize([
								connection:connection, disk:[id:volumeId, size:updateProps.maxStorage]
							])
							if (resizeResults.success == true) {
								existing.maxStorage = updateProps.maxStorage.toLong()
								morpheus.async.storageVolume.save([existing]).blockingGet()
							} else {
								rtn.setError("Failed to expand Disk: ${existing.name}")
							}
						}
					}
				}

				//delete any removed volumes
				resizeRequest.volumesDelete.each { volume ->
					log.info("Deleting volume : ${volume.externalId}")
					def volumeId = volume.externalId
					def detachResults = OlvmComputeUtility.detachVolume([volumeId:volumeId, vmId: server.externalId, connection:connection])
					if (detachResults.success == true) {
						OlvmComputeUtility.deleteVolume([volumeId:volumeId, connection:connection])
						morpheus.async.storageVolume.remove([volume], server, true).blockingGet()
					}
				}

				//network adapters
				resizeRequest?.interfacesAdd?.eachWithIndex { networkAdd, index ->
					log.info("adding network: ${networkAdd}")
					def newIndex = server.interfaces?.size()
					Network networkObj = morpheus.async.network.listById([networkAdd.network.id.toLong()]).firstOrError().blockingGet()
					def platform = server.platform
					def nicName
					if(platform == 'windows') {
						nicName = (index == 0) ? 'Ethernet' : 'Ethernet ' + (index + 1)
					} else {
						nicName = "eth${index}"
					}
					def networkConfig = [
						connection:connection, vmId:server.externalId, networkId:networkObj.externalId, deviceName:nicName
					]
					def networkResults = OlvmComputeUtility.addNetworkInterface(networkConfig)
					if (networkResults.success == true) {
						def newInterface = new ComputeServerInterface([
							externalId      : networkResults.data.id(),
							uniqueId        : "morpheus-nic-${serverId}-${newIndex}",
							name            : nicName,
							network         : networkObj,
							displayOrder    : newIndex,
							primaryInterface: networkAdd?.network?.isPrimary ? true : false
						])

						newInterface = morpheus.async.computeServer.computeServerInterface.create(newInterface).blockingGet()
						server.interfaces += newInterface
						server = saveAndGet(server)
					}
				}

				resizeRequest?.interfacesDelete?.eachWithIndex { networkDelete, index ->
					def deleteConfig = [
						connection: connection, vmId: server.externalId, nicId: networkDelete.externalId
					]
					def deleteResults = OlvmComputeUtility.deleteNetworkInterface(deleteConfig)
					if (deleteResults.success == true) {
						morpheus.async.computeServer.computeServerInterface.remove([networkDelete], server).blockingGet()
						server = morpheus.async.computeServer.get(server.id).blockingGet()
					}
				}
				rtn.success = true
			}
			def vmOpts = [connection:connection, server:server]
			def startResults = OlvmComputeUtility.startVm(vmOpts)
			if(startResults.success == true) {
				// refreshServerIp(server, vmOpts)
			}
		}
		catch(Throwable t) {
			log.error("Error resizing olvm instance to ${plan.name}", t)
			rtn.success = false
			rtn.msg = "Error resizing olvm instance to ${plan.name} ${t.message}"
			rtn.error= "Error resizing olvm instance to ${plan.name} ${t.message}"
		}
		finally {
			client?.shutdownClient()
		}
		return new ServiceResponse(success: rtn.success, data: [supported: rtn.supported])
	}

	def finalizeVm(Map runConfig, ProvisionResponse provisionResponse, Map runResults) {
		OlvmVersion.setMDC()
		log.debug("finalizeVm: provisionResponse.success=${provisionResponse.success}, sshHost=${runResults.sshHost}, serverId=${runConfig.serverId}")
		ComputeServer server = morpheus.async.computeServer.get(runConfig.serverId).blockingGet()
		try {
			if(provisionResponse.success == true) {
				server.sshHost = runResults.sshHost
				server.status = 'provisioned'
				server.statusDate = new Date()
				server.serverType = 'vm'
				server.osDevice = '/dev/vda'
				server.dataDevice = server.volumes?.size() > 1 ? '/dev/vdb' : '/dev/vda'
				server.lvmEnabled = server.volumes?.size() > 1
				server.managed = true
				if(runResults.newPassword)
					server.sshPassword = runResults.newPassword
				server.capacityInfo = new ComputeCapacityInfo(maxCores:1, maxMemory:runConfig.maxMemory,
					maxStorage:runConfig.maxStorage)
				log.debug("finalizeVm: saving server ${server.id} with status=provisioned, sshHost=${server.sshHost}, managed=${server.managed}")
				saveAndGet(server)
			} else {
				log.warn("finalizeVm: skipping finalize because provisionResponse.success is false")
			}
		}
		catch(Throwable t) {
			log.error("finalizeVm error: ${t.message}", t)
			provisionResponse.setError("failed to run server: ${t.message}")
		}
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		// Re-fetch the server to get the freshest sshHost value (set by agent call-home).
		def server = morpheus.async.computeServer.get(workload.server.id).blockingGet() ?: workload.server
		if (!server) {
			return ServiceResponse.success()
		}
		if (server.internalIp) {
			log.debug("finalizeWorkload: server ${server.id} already has internalIp=${server.internalIp}, nothing to do")
			return ServiceResponse.success()
		}

		// Primary source: Morpheus agent called home and set sshHost.
		def ipToUse = server.sshHost

		// Fallback: query the OLVM API for the IP reported by qemu-guest-agent.
		// This works when qemu-guest-agent is installed in the VM template, even if
		// the Morpheus agent can't reach the appliance (e.g. dev/NAT environments).
		if (!ipToUse && server.externalId) {
			log.info("finalizeWorkload: sshHost is null for server ${server.id}, querying OLVM API for current IP")
			try {
				def connection = OlvmComputeUtility.getToken(server.cloud, morpheus)
				def serverDetail = OlvmComputeUtility.getServerDetail([server: server, connection: connection])
				def olvmIp = serverDetail?.data?.ipV4?.find { it }
				if (olvmIp) {
					log.info("finalizeWorkload: got IP from OLVM API: ${olvmIp} (server ${server.id})")
					ipToUse = olvmIp
				} else {
					log.warn("finalizeWorkload: OLVM API returned no IP for server ${server.id} " +
						"(externalId=${server.externalId}) — install qemu-guest-agent in the VM template " +
						"or ensure the Morpheus appliance URL is reachable from the VM network")
				}
			} catch (Exception e) {
				log.error("finalizeWorkload: error querying OLVM API for IP (server ${server.id}): ${e.message}", e)
			}
		}

		if (ipToUse) {
			log.info("finalizeWorkload: setting internalIp=${ipToUse} externalIp=${ipToUse} for server ${server.id}")
			server.internalIp = ipToUse
			server.externalIp = ipToUse
			morpheus.async.computeServer.bulkSave([server]).blockingGet()
		}
		return ServiceResponse.success()
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		return stopServer(workload.server)
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		return startServer(workload.server)
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.debug "restartWorkload: ${workload}"
		ServiceResponse stopResult = stopWorkload(workload)
		if (stopResult.success) {
			return startWorkload(workload)
		}
		return stopResult
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		log.debug "removeWorkload: ${workload} ${opts}"
		return deleteServer(workload.server)
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		OlvmVersion.setMDC()
		ProvisionResponse rtn = new ProvisionResponse()
		def serverUuid = server.externalId
		if (!server || !serverUuid) {
			return ServiceResponse.error("Could not find server uuid")
		}
		Map connection = OlvmComputeUtility.getToken(server.cloud, morpheus)
		// The VM was already verified as running by insertVm/checkServerReady inside runWorkload.
		// Calling checkServerReady again here would time out if the VM is mid-reboot (e.g. Ubuntu
		// cloud-init first-boot), causing a false "Server not ready" failure.
		// Do a single direct lookup instead. If the VM is temporarily between states (rebooting),
		// return success with no IP — finalizeWorkload will capture the DHCP address from the
		// Morpheus agent call-home once provisioning completes.
		def serverDetails = OlvmComputeUtility.getServerDetail([connection:connection, server:server])
		rtn.externalId = serverUuid
		rtn.success = true
		if (serverDetails.success && serverDetails.data) {
			// Prefer IP reported by OLVM guest agent; fall back to interface IP (static),
			// then internalIp, then sshHost (set by Morpheus agent call-home on DHCP VMs)
			rtn.privateIp = serverDetails.data.ipV4?.find { it } ?:
				server.interfaces?.find { it.primaryInterface }?.ipAddress ?:
				server.internalIp ?:
				server.sshHost
			rtn.publicIp = rtn.privateIp
			rtn.hostname = serverDetails.data.hostname
			log.info("getServerDetails: ip=${rtn.privateIp} (olvm=${serverDetails.data.ipV4}, interfaceIp=${server.interfaces?.find{it.primaryInterface}?.ipAddress}, internalIp=${server.internalIp}, sshHost=${server.sshHost})")
		} else {
			// VM is temporarily unreachable (rebooting after cloud-init?). Proceed without IP;
			// the static interface IP or finalizeWorkload fallback will populate it later.
			rtn.privateIp = server.interfaces?.find { it.primaryInterface }?.ipAddress ?: server.internalIp
			rtn.publicIp = rtn.privateIp
			log.info("getServerDetails: VM ${serverUuid} not immediately reachable (may be rebooting) - proceeding without IP, will capture in finalizeWorkload")
		}
		return ServiceResponse.success(rtn)
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer server) {
		log.debug("stopServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			Map connection
			connection = OlvmComputeUtility.getToken(server.cloud, morpheus)
			def stopResult = OlvmComputeUtility.stopVm([server:server, connection:connection])

			if (stopResult.success) {
				return ServiceResponse.success()
			}
			else {
				return ServiceResponse.error('Failed to stop vm')
			}
		}
		else {
			log.info("stopServer - ignoring request for unmanaged instance")
		}
		ServiceResponse.success()
	}

	/**
	 * Start the server
	 * @param server to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer server) {
		log.debug("startServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			Map connection
			connection = OlvmComputeUtility.getToken(server.cloud, morpheus)
			def startResult = OlvmComputeUtility.startVm([server:server, connection:connection])

			if (startResult.success == true) {
				return ServiceResponse.success()
			}
			else {
				return ServiceResponse.error('Failed to start vm')
			}
		}
		else {
			log.info("startServer - ignoring request for unmanaged instance")
		}
		ServiceResponse.success()
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return PROVISION_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return 'OLVM'
	}

	/**
	 * Delete the server
	 * @param server to start
	 * @return Response from API
	 */
	ServiceResponse deleteServer(ComputeServer server) {
		log.debug("deleteServer: ${server}")
		if(server?.externalId && (server.managed == true || server.computeServerType?.controlPower)) {
			def deleteResult = OlvmComputeUtility.deleteServer([cloud:server.cloud, server:server], morpheus)

			if (deleteResult.success) {
				return ServiceResponse.success()
			} else {
				return ServiceResponse.error('Failed to remove vm')
			}
		} else {
			log.info("deleteServer - ignoring request for unmanaged instance")
		}
		return ServiceResponse.success()
	}

	protected getWorkloadImage(Workload workload, Map opts = [:]) {
		VirtualImage rtn
		def containerConfig = workload.getConfigMap()
		def imageType = containerConfig.imageType ?: 'default'
        if(opts.config?.imageId){
            rtn = morpheus.async.virtualImage.get(opts.config.imageId as Long).blockingGet()
        } else if(imageType == 'private' && containerConfig.imageId) {
			rtn = morpheus.async.virtualImage.get(containerConfig.imageId as Long).blockingGet()
		}
		else if(imageType == 'local' && (containerConfig.localImageId || containerConfig.template)) {
			Long localImageId = getImageId(containerConfig.localImageId) ?: getImageId(containerConfig.template)
			if(localImageId) {
				rtn = morpheus.async.virtualImage.location.get(localImageId).blockingGet()
			}
		}
		else if(workload.workloadType.virtualImage) {
			rtn = workload.workloadType.virtualImage
		}
		else if (containerConfig.template) {
			rtn = morpheus.async.virtualImage.get(containerConfig.template).blockingGet()
		}
		return rtn
	}

	protected VirtualImageLocation ensureVirtualImageLocation(Map connection, VirtualImage virtualImage, Cloud cloud) {
		def rtn = virtualImage.imageLocations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id}
		if(!rtn) {
			rtn = virtualImage.imageLocations?.find{it.refType == 'ComputeZone' && it.refId == cloud.id}
		}
		if(!rtn) {
			def templateResults
			if(!virtualImage.externalId) {
				//load image by name
				templateResults = OlvmComputeUtility.loadImage([connection: connection, imageName: virtualImage.name, isPublic: true])
			}
			else {
				templateResults = OlvmComputeUtility.loadImage([connection: connection, imageId: virtualImage.externalId])
			}
			if (templateResults.success && templateResults.data.template) {
				def template = templateResults.data.template
				def newLocation = new VirtualImageLocation(
					virtualImage:virtualImage, imageName:virtualImage.name, externalId:template.id,
					code: "olvm.plugin.template.${cloud.id}.${template.id}",
					refType:'ComputeZone', refId:cloud.id
				)
				newLocation = morpheus.async.virtualImage.location.create(newLocation, cloud).blockingGet()
				rtn = newLocation
			}
		}
		return rtn
	}

	protected buildHostRunConfig(ComputeServer server, HostRequest hostRequest, VirtualImage virtualImage, Map connection, Map opts) {
		Cloud cloud = server.cloud
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}

		def maxMemory = server.maxMemory
		def maxStorage = rootVolume.getMaxStorage()
		def serverConfig = server.getConfigMap()

		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, hostRequest.networkConfiguration, connection, serverConfig, opts)

		runConfig += [
			name              : server.name,
			account 		  : server.account,
			osDiskSize		  : maxStorage,
			maxStorage        : maxStorage,
			maxMemory		  : maxMemory,
			applianceServerUrl: hostRequest.cloudConfigOpts?.applianceUrl,
			timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
			proxySettings     : hostRequest.proxyConfiguration,
			noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
			installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
			userConfig		  : hostRequest.usersConfiguration,
			cloudConfig		  : hostRequest.cloudConfigUser,
			networkConfig	  : hostRequest.networkConfiguration,
			workloadConfig    : serverConfig
		]

		log.debug("buildHostRunConfig - Cloud-init config length: ${runConfig.cloudConfig?.length()}")

		return runConfig
	}

	protected buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, VirtualImage virtualImage, Map connection, Map opts) {
		log.debug("buildRunConfig: {}, {}, {}, {}", workload, workloadRequest, virtualImage, opts)
		Map workloadConfig = workload.getConfigMap()
		ComputeServer server = workload.server
		Cloud cloud = server.cloud
		StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}

		def maxMemory = server.maxMemory
		def maxStorage = rootVolume.getMaxStorage()

		def runConfig = [:] + opts + buildRunConfig(server, virtualImage, workloadRequest.networkConfiguration, connection, workloadConfig, opts)

		def stateless = workloadConfig.stateless?.toString() == 'true' || workloadConfig.stateless == true
		def diskProvisioning = workloadConfig.diskProvisioning ?: 'thin'
		def cloneType = workloadConfig.cloneType ?: 'linked'

		if (stateless && diskProvisioning == 'preallocated') {
			log.warn("buildWorkloadRunConfig: stateless=true requires thin (COW) disk provisioning — overriding diskProvisioning to 'thin'")
			diskProvisioning = 'thin'
		}

		def hostRef = null
		def hostAffinity = workloadConfig.hostAffinity ?: 'migratable'
		if (workloadConfig.hostId) {
			// hostId is the OLVM externalId (UUID) returned directly by olvmHosts option source
			hostRef = workloadConfig.hostId
		}

		runConfig += [
			name              : server.name,
			instanceId		  : workload.instance.id,
			containerId       : workload.id,
			account 		  : server.account,
			osDiskSize		  : maxStorage.div(ComputeUtility.ONE_GIGABYTE),
			maxStorage        : maxStorage,
			maxMemory		  : maxMemory,
			applianceServerUrl: workloadRequest.cloudConfigOpts?.applianceUrl,
			workloadConfig    : workloadConfig,
			timezone          : (server.getConfigProperty('timezone') ?: cloud.timezone),
			proxySettings     : workloadRequest.proxyConfiguration,
			noAgent           : (opts.config?.containsKey("noAgent") == true && opts.config.noAgent == true),
			installAgent      : (opts.config?.containsKey("noAgent") == false || (opts.config?.containsKey("noAgent") && opts.config.noAgent != true)),
			userConfig        : workloadRequest.usersConfiguration,
			cloudConfig	      : workloadRequest.cloudConfigUser,
			cloudConfigNetwork: workloadRequest.cloudConfigNetwork,
			networkConfig	  : workloadRequest.networkConfiguration,
			stateless         : stateless,
			diskProvisioning  : diskProvisioning,
			cloneType         : cloneType,
			hostRef           : hostRef,
			hostAffinity      : hostAffinity,
		]

		log.debug("buildWorkloadRunConfig - Cloud-init config length: ${runConfig.cloudConfig?.length()}")

		return runConfig
	}

	/**
	 * Generate network configuration section for cloud-init
	 * @param networkConfig The network configuration from runConfig
	 * @return YAML string with network configuration
	 */
	// protected String buildCloudInitNetworkConfig(def networkConfig) {
	// 	def networkYaml = ""
		
	// 	try {
	// 		def primaryInterface = networkConfig?.primaryInterface
			
	// 		if (!primaryInterface) {
	// 			log.debug("No primary interface found in networkConfig")
	// 			return networkYaml
	// 		}
			
	// 		// Only add network config for static IP configurations
	// 		if (!primaryInterface.doStatic || primaryInterface.doDhcp) {
	// 			log.debug("Interface is DHCP or not static, skipping network config")
	// 			return networkYaml
	// 		}
			
	// 		def interfaceName = primaryInterface.name ?: 'eth0'
	// 		def ipAddress = primaryInterface.ipAddress
	// 		def gateway = primaryInterface.gateway
	// 		def netmask = primaryInterface.netmask
			
	// 		if (!ipAddress) {
	// 			log.debug("No IP address found for static configuration")
	// 			return networkYaml
	// 		}
			
	// 		// Convert netmask to CIDR prefix
	// 		def cidrPrefix = netmaskToCidr(netmask)
			
	// 		// Parse DNS servers
	// 		def dnsServers = []
	// 		if (primaryInterface.dnsServers) {
	// 			dnsServers = primaryInterface.dnsServers.split(',').collect { it.trim() }.findAll { it }
	// 		}
			
	// 		// Build cloud-init v2 network configuration
	// 		networkYaml = "\nnetwork:\n"
	// 		networkYaml += "  version: 2\n"
	// 		networkYaml += "  ethernets:\n"
	// 		networkYaml += "    ${interfaceName}:\n"
	// 		networkYaml += "      addresses:\n"
	// 		networkYaml += "      - ${ipAddress}/${cidrPrefix}\n"
			
	// 		if (gateway) {
	// 			networkYaml += "      gateway4: ${gateway}\n"
	// 		}
			
	// 		if (dnsServers) {
	// 			networkYaml += "      nameservers:\n"
	// 			networkYaml += "        addresses:\n"
	// 			dnsServers.each { dns ->
	// 				networkYaml += "        - ${dns}\n"
	// 			}
	// 		}
			
	// 		log.debug("Generated cloud-init network config for ${interfaceName}: IP=${ipAddress}/${cidrPrefix}, Gateway=${gateway}")
			
	// 	} catch (Exception e) {
	// 		log.error("Error building cloud-init network config: ${e.message}", e)
	// 	}
		
	// 	return networkYaml
	// }

	/**
	 * Convert netmask to CIDR prefix length.
	 * @param netmask Netmask in dotted decimal format (e.g., 255.255.252.0)
	 * @return CIDR prefix (e.g., 22), or 24 as a safe default if netmask is missing/invalid
	 */
	protected Integer netmaskToCidr(String netmask) {
		if (!netmask || netmask == '0.0.0.0') {
			return 24
		}
		try {
			def parts = netmask.split('\\.')
			if (parts.size() != 4) {
				return 24
			}
			def binary = parts.collect {
				Integer.parseInt(it).toString(2).padLeft(8, '0')
			}.join('')
			def cidr = binary.count('1')
			return cidr > 0 ? cidr : 24
		} catch (Exception e) {
			log.error("Error converting netmask ${netmask} to CIDR: ${e.message}", e)
			return 24
		}
	}

	/**
	 * Resolve CIDR prefix for a network interface, using subnet/network metadata first
	 * and falling back to netmask conversion. Logs a warning if all sources are bogus.
	 */
	protected Integer resolveCidr(primaryInterface) {
		def cidrFromSubnetPrefixLen = primaryInterface.subnet?.prefixLength as Integer
		if (cidrFromSubnetPrefixLen && cidrFromSubnetPrefixLen >= 8) {
			return cidrFromSubnetPrefixLen
		}
		if (primaryInterface.subnet?.cidr?.contains('/')) {
			def parsed = primaryInterface.subnet.cidr.split('/')[1] as Integer
			if (parsed >= 8) return parsed
		}
		if (primaryInterface.network?.cidr?.contains('/')) {
			def parsed = primaryInterface.network.cidr.split('/')[1] as Integer
			if (parsed >= 8) return parsed
		}
		def cidr = netmaskToCidr(primaryInterface.netmask as String)
		if (cidr < 8) {
			log.warn("enhanceCloudInitConfig: bogus CIDR from all sources (subnetPrefixLen=${primaryInterface.subnet?.prefixLength}, subnetCidr=${primaryInterface.subnet?.cidr}, networkCidr=${primaryInterface.network?.cidr}, netmask=${primaryInterface.netmask}); defaulting to /24. Configure the network CIDR in Morpheus.")
			return 24
		}
		return cidr
	}



	protected buildRunConfig(ComputeServer server, VirtualImage virtualImage, NetworkConfiguration networkConfiguration, Map connection, config, Map opts) {
		log.debug("buildRunConfig: {}, {}, {}, {}, {}", server, virtualImage, networkConfiguration, config, opts)
		Cloud cloud = server.cloud
		def network = networkConfiguration.primaryInterface?.network
		if(!network && server.interfaces) {
			network = server.interfaces.find {it.primaryInterface}?.network
		}
		def rootVolume = server.volumes?.find{it.rootVolume == true}
		def dataDisks = server?.volumes?.findAll{it.rootVolume == false}?.sort{it.id}
		def maxStorage
		if(rootVolume) {
			maxStorage = rootVolume.maxStorage
		} else {
			maxStorage = config.maxStorage ?: server.plan.maxStorage
		}

		// get data center and cluster information
		def zonePoolService = morpheus.async.cloud.pool

		// config.clusterId/datacenterId come from the wizard-selected options and are present
		// for a normal workload provision. K8s cluster host nodes (master and workers) are
		// auto-provisioned without any pool selection at all (opts has no config, and
		// config.resourcePoolId is blank) - fall back to a sibling node in the same serverGroup
		// (e.g. the master, which was provisioned normally and already has a resourcePool set).
		Long clusterPoolId = (config.clusterId ?: config.resourcePoolId ?: opts.config?.clusterId ?: opts.config?.resourcePoolId ?: server.resourcePool?.id) as Long
		if (!clusterPoolId && server.serverGroup?.id) {
			def siblingServer = morpheus.async.computeServer.list(
				new DataQuery().withFilter('serverGroup.id', server.serverGroup.id)
			).toList().blockingGet().find { it.id != server.id && (it.resourcePool?.id || it.getConfigMap()?.resourcePoolId) }
			if (siblingServer) {
				clusterPoolId = (siblingServer.resourcePool?.id ?: siblingServer.getConfigMap()?.resourcePoolId) as Long
				log.debug("buildRunConfig: resolved clusterPoolId=${clusterPoolId} from sibling server ${siblingServer.id} in serverGroup ${server.serverGroup.id}")
			}
		}
		if (!clusterPoolId) {
			log.error("buildRunConfig: unable to determine cluster for server ${server.id} - no clusterId/resourcePoolId in config, opts.config, resourcePool, or serverGroup ${server.serverGroup?.id} siblings")
			throw new IllegalStateException("Unable to determine cluster for server ${server.id}: no clusterId/resourcePoolId in config, opts.config, resourcePool, or serverGroup siblings")
		}
		def cluster = zonePoolService.get(clusterPoolId).blockingGet()

		def datacenter
		if (cloud.configMap.datacenter == 'all') {
			Long datacenterPoolId = (config.datacenterId ?: opts.config?.datacenterId) as Long
			if (datacenterPoolId) {
				datacenter = zonePoolService.get(datacenterPoolId).blockingGet()
			}
			else {
				// derive the datacenter from the resolved cluster's OLVM data_center reference
				def clusterDetail = OlvmComputeUtility.getCluster([connection:connection, clusterId:cluster.externalId])
				def datacenterExternalId = clusterDetail.data?.data_center?.id
				datacenter = zonePoolService.find(
					new DataQuery().withFilter(new DataFilter('externalId', datacenterExternalId))
				).blockingGet()
				log.debug("buildRunConfig: config.datacenterId missing, derived datacenter ${datacenter?.name} from cluster ${cluster.name}")
			}
		}
		else {
			datacenter = zonePoolService.find(
				new DataQuery().withFilter(new DataFilter('externalId', cloud.configMap.datacenter))
			).blockingGet()
		}

		def runConfig = [
			serverId:server.id,
			connection:connection,
			name:server.name,
			securityRef:config.securityId,
			networkRef:network?.externalId,
			datacenterRef:datacenter.externalId,
			datacenterName:datacenter.name,
			clusterRef:cluster.externalId,
			clusterName:cluster.name,
			server:server,
			imageType:virtualImage.imageType,
			serverOs:server.serverOs ?: virtualImage.osType,
			osType:(virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
			platform:(virtualImage.osType?.platform == 'windows' ? 'windows' : 'linux') ?: virtualImage.platform,
			osDiskSize:maxStorage.div(ComputeUtility.ONE_GIGABYTE),
			maxStorage:maxStorage,
			osDiskName:'/dev/vda1',
			dataDisks:dataDisks,
			rootVolume:rootVolume,
			virtualImage:virtualImage,
			hostname:server.getExternalHostname(),
			hosts:server.getExternalHostname(),
			diskList:[],
			domainName:server.getExternalDomain(),
			serverInterfaces:server.interfaces,
			fqdn:server.getExternalHostname() + '.' + server.getExternalDomain()
		]

		runConfig.virtualImageLocation = ensureVirtualImageLocation(connection, virtualImage, server.cloud)

		log.debug("Setting snapshot image refs opts.snapshotImageRef: ${opts.snapshotImageRef},  ${opts.rootSnapshotId}")
		// use selected provision image
		runConfig.imageRef = runConfig.virtualImageLocation?.externalId

		return runConfig
	}

		/**
	 * Enhance cloud-init config with static network configuration.
	 * - Ubuntu (18.04+): writes a netplan YAML to /etc/netplan/99-morpheus.yaml for both DHCP and static IP,
	 *   since Ubuntu's netplan won't enable DHCP4 unless explicitly told to.
	 * - OEL/RHEL: writes a NetworkManager keyfile to /etc/NetworkManager/system-connections/ for static IP.
	 *   DHCP works by default on OEL/RHEL via NetworkManager and needs no extra config.
	 */
	protected String enhanceCloudInitConfig(String cloudConfig, def hostname, def domainName, def networkConfig, def serverOs = null, def virtualImage = null) {
		if (!cloudConfig) {
			cloudConfig = "#cloud-config\n"
		}

		// Determine whether this is a netplan (Ubuntu/Debian) or NM (OEL/RHEL) system.
		// server.serverOs is unreliable for K8S cluster nodes: Morpheus may set it to a
		// generic "Linux" OsType even when the VM runs Ubuntu. We therefore check multiple
		// sources in priority order and treat any Ubuntu/Debian signal as authoritative.
		def osNameLower     = serverOs?.name?.toLowerCase()                   ?: ''
		def imgOsNameLower  = virtualImage?.osType?.name?.toLowerCase()       ?: ''
		def imgNameLower    = virtualImage?.name?.toLowerCase()                ?: ''

		def ubuntuSignal = { String s -> s.contains('ubuntu') || s.contains('debian') || s.contains('mint') }
		def isUbuntu = ubuntuSignal(osNameLower) || ubuntuSignal(imgOsNameLower) || ubuntuSignal(imgNameLower)

		// Ubuntu/Debian use netplan; OEL/RHEL use NetworkManager.
		def isOel = !isUbuntu

		// Suppress package operations that can trigger a mid-boot reboot on any OS,
		// which would kill cloud-init before the Morpheus agent is installed.
		if (cloudConfig =~ /(?m)^package_upgrade:\s*true\s*$/) {
			cloudConfig = cloudConfig.replaceAll(/(?m)^package_upgrade:\s*true\s*$/, 'package_upgrade: false')
			log.info("enhanceCloudInitConfig: suppressed package_upgrade: true to prevent mid-boot reboot before agent install")
		}
		if (cloudConfig =~ /(?m)^package_reboot_if_required:\s*true\s*$/) {
			cloudConfig = cloudConfig.replaceAll(/(?m)^package_reboot_if_required:\s*true\s*$/, 'package_reboot_if_required: false')
			log.info("enhanceCloudInitConfig: suppressed package_reboot_if_required: true to prevent mid-boot reboot before agent install")
		}

		if (isOel) {
			// OEL/RHEL-specific: suppress package_update and strip packages list to prevent OOM.
			// dnf downloads full repo metadata (~200 MB on OEL9) even for a single package,
			// which can OOM-kill cloud-final before runcmd runs. apt on Ubuntu does not have
			// this problem, so these suppressions must not be applied to Ubuntu/Debian nodes
			// (e.g. K8S cluster nodes) that rely on apt package installation during provisioning.
			if (cloudConfig =~ /(?m)^package_update:\s*true\s*$/) {
				cloudConfig = cloudConfig.replaceAll(/(?m)^package_update:\s*true\s*$/, 'package_update: false')
				log.info("enhanceCloudInitConfig: suppressed package_update: true to avoid OOM during provisioning")
			}
			if (cloudConfig =~ /(?ms)^packages:\n(^[ \t]*-[^\n]*\n)+/) {
				cloudConfig = cloudConfig.replaceAll(/(?ms)^packages:\n(^[ \t]*-[^\n]*\n)+/, '')
				log.info("enhanceCloudInitConfig: removed packages: list to prevent OOM during provisioning")
			}
		}

		// Fix schema type errors from the Morpheus core cloud-config template:
		// disable_root and ssh_deletekeys must be booleans, not integer 0 or quoted string.
		if (cloudConfig =~ /(?m)^disable_root:\s*0\s*$/) {
			cloudConfig = cloudConfig.replaceAll(/(?m)^disable_root:\s*0\s*$/, 'disable_root: false')
			log.info("enhanceCloudInitConfig: fixed disable_root: 0 -> false")
		}
		if (cloudConfig =~ /(?m)^ssh_deletekeys:\s*'false'\s*$/) {
			cloudConfig = cloudConfig.replaceAll(/(?m)^ssh_deletekeys:\s*'false'\s*$/, 'ssh_deletekeys: false')
			log.info("enhanceCloudInitConfig: fixed ssh_deletekeys: 'false' -> false")
		}

		log.debug("enhanceCloudInitConfig: cloud-config content:\n${cloudConfig}")

		def writeFilesEntries = []
		def runcmdEntries = []

		def primaryInterface = networkConfig?.primaryInterface
		def nicName = primaryInterface?.name ?: 'eth0'
		def isNetplan = !isOel
		log.info("enhanceCloudInitConfig: isNetplan=${isNetplan}, osType='${serverOs?.name}', imgOsType='${virtualImage?.osType?.name}', imgName='${virtualImage?.name}', nic=${nicName}, doStatic=${primaryInterface?.doStatic}, doDhcp=${primaryInterface?.doDhcp}, ip=${primaryInterface?.ipAddress}")

		// For Ubuntu/Debian (netplan): write netplan YAML for static IP via write_files.
		// oVirt's cloud-init API only accepts custom_script (user-data); there is no supported
		// way to pass network_data through oVirt's initialization API. For DHCP, Ubuntu's
		// default cloud-init behavior is sufficient.
		//
		// IMPORTANT: we do NOT hard-code the NIC name. Ubuntu 20.04+ in OLVM/KVM uses
		// predictable interface names (e.g., ens3, enp1s0) that differ from 'eth0' and are
		// not known at provisioning time (they depend on PCI slot assignment). Using
		// match.name: "e*" targets any ethernet interface (eth0, ens*, enp*, etc.), the same
		// approach used for OEL via the interface-name=e*; glob in the NM keyfile.
		if (isNetplan && primaryInterface && primaryInterface.doStatic && !primaryInterface.doDhcp) {
			def ipAddress = primaryInterface.ipAddress
			def netmask = primaryInterface.netmask
			def gateway = primaryInterface.gateway

			if (ipAddress && netmask) {
				def cidr = resolveCidr(primaryInterface)
				log.info("enhanceCloudInitConfig: Ubuntu static IP netplan (match e*): ${ipAddress}/${cidr}, gateway=${gateway}")
				def netplanLines = []
				netplanLines << "network:"
				netplanLines << "  version: 2"
				netplanLines << "  ethernets:"
				netplanLines << "    morpheus-static:"
				netplanLines << "      match:"
				netplanLines << "        name: \"e*\""
				netplanLines << "      dhcp4: false"
				netplanLines << "      addresses:"
				netplanLines << "        - ${ipAddress}/${cidr}"
				if (gateway) {
					netplanLines << "      routes:"
					netplanLines << "        - to: default"
					netplanLines << "          via: ${gateway}"
				}
				def dnsServers = primaryInterface.dnsServers?.split(',')?.collect { it.trim() }?.findAll { it }
				if (dnsServers) {
					netplanLines << "      nameservers:"
					netplanLines << "        addresses: [${dnsServers.join(', ')}]"
				}
				def fileEntryLines = []
				fileEntryLines << "- path: /etc/netplan/99-morpheus.yaml"
				fileEntryLines << "  content: |"
				netplanLines.each { line -> fileEntryLines << "    ${line}" }
				fileEntryLines << "  permissions: '0600'"
				fileEntryLines << "  owner: root:root"
				writeFilesEntries << fileEntryLines.join('\n')

				// Prevent cloud-init from regenerating 50-cloud-init.yaml with DHCP on reboot,
				// which would conflict with our static IP config.
				def disableCiLines = []
				disableCiLines << "- path: /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg"
				disableCiLines << "  content: |"
				disableCiLines << "    network: {config: disabled}"
				disableCiLines << "  permissions: '0644'"
				disableCiLines << "  owner: root:root"
				writeFilesEntries << disableCiLines.join('\n')

				// Remove cloud-init's generated DHCP netplan BEFORE applying ours.
				// Ubuntu 24.04 cloud images ship with /etc/netplan/50-cloud-init.yaml
				// containing a named interface entry (e.g., ens3: dhcp4: true). With both
				// files present, netplan sees two conflicting definitions for the same physical
				// interface (one named, one via match.name glob) and refuses to apply, silently
				// leaving the VM with no IP. Removing 50-cloud-init.yaml first ensures our
				// 99-morpheus.yaml is the sole config when netplan apply runs.
				runcmdEntries << "- rm -f /etc/netplan/50-cloud-init.yaml"
				runcmdEntries << "- netplan apply"
				runcmdEntries << "- |"
				runcmdEntries << "  for i in \$(seq 1 30); do"
				runcmdEntries << "    ip -4 addr show scope global | grep -q inet && break"
				runcmdEntries << "    sleep 1"
				runcmdEntries << "  done"
				runcmdEntries << "- |"
				runcmdEntries << "  for i in \$(seq 1 30); do"
				runcmdEntries << "    getent hosts google.com 2>/dev/null && break"
				runcmdEntries << "    sleep 1"
				runcmdEntries << "  done"
			}
		} else if (isOel && primaryInterface && primaryInterface.doStatic && !primaryInterface.doDhcp) {
			// Non-netplan (OEL/RHEL): write a NetworkManager keyfile for static IP.
			def ipAddress = primaryInterface.ipAddress
			def netmask = primaryInterface.netmask
			def gateway = primaryInterface.gateway
			log.debug("enhanceCloudInitConfig: building NM keyfile static IP for interface '${nicName}' with IP=${ipAddress}/${netmask}, gateway=${gateway}")

			if (ipAddress && netmask) {
				log.debug("Adding static network configuration to cloud-init: ${nicName}=${ipAddress}")

				def cidr = resolveCidr(primaryInterface)

				// Build NetworkManager keyfile content (supported on OEL/RHEL 7, 8, and 9)
				def nmLines = []
				nmLines << "[connection]"
				nmLines << "id=${nicName}"
				nmLines << "type=ethernet"
				nmLines << "autoconnect=yes"
				nmLines << ""
				nmLines << "[match]"
				// Glob matches eth0, enp1s0, ens3, etc. — the predictable NIC name used
				// by the guest OS is not known at provisioning time. The [match] section
				// (unlike [connection] interface-name) supports shell globs.
				nmLines << "interface-name=e*;"
				nmLines << ""
				nmLines << "[ethernet]"
				nmLines << ""
				nmLines << "[ipv4]"
				nmLines << "method=manual"
				nmLines << "address1=${ipAddress}/${cidr}"
				if (gateway) {
					nmLines << "gateway=${gateway}"
				}
				def dnsServers = primaryInterface.dnsServers?.split(',')?.collect { it.trim() }?.findAll { it }
				if (dnsServers) {
					nmLines << "dns=${dnsServers.join(';')};"
				}
				nmLines << ""
				nmLines << "[ipv6]"
				nmLines << "method=auto"

				// Build write_files entry
				def fileEntryLines = []
				fileEntryLines << "- path: /etc/NetworkManager/system-connections/${nicName}.nmconnection"
				fileEntryLines << "  content: |"
				nmLines.each { line -> fileEntryLines << "    ${line}" }
				fileEntryLines << "  permissions: '0600'"
				fileEntryLines << "  owner: root:root"
				writeFilesEntries << fileEntryLines.join('\n')

				runcmdEntries << "- nmcli connection reload"
				runcmdEntries << "- nmcli connection up id ${nicName}"
				runcmdEntries << "- systemctl restart systemd-resolved 2>/dev/null || true"
				runcmdEntries << "- |"
				runcmdEntries << "  for i in \$(seq 1 30); do"
				runcmdEntries << "    grep -q '^nameserver' /run/systemd/resolve/resolv.conf 2>/dev/null && break"
				runcmdEntries << "    sleep 1"
				runcmdEntries << "  done"
			}
		} else if (isOel) {
			// OEL/RHEL DHCP: some templates use NetworkManager, others (e.g. minimal OEL 9
			// cloud images) use systemd-networkd. We write config files for both and use
			// a runtime runcmd to activate whichever stack is present.
			//
			// NetworkManager keyfile (for NM-based templates):
			// Templates commonly have an NM connection profile tied to the original
			// interface name (e.g. enp0s2). The provisioned VM may get a different
			// PCI slot (e.g. enp1s0), so no profile matches and NM leaves the interface
			// disconnected. We write a DHCP keyfile using the [match] glob so NM brings
			// up whichever ethernet interface the VM actually has. DNS servers from Morpheus
			// are added explicitly because some DHCP servers don't send option 6 (DNS).
			def nmDhcpLines = []
			nmDhcpLines << "[connection]"
			nmDhcpLines << "id=morpheus-dhcp"
			nmDhcpLines << "type=ethernet"
			nmDhcpLines << "autoconnect=yes"
			nmDhcpLines << ""
			nmDhcpLines << "[match]"
			nmDhcpLines << "interface-name=e*;"
			nmDhcpLines << ""
			nmDhcpLines << "[ethernet]"
			nmDhcpLines << ""
			nmDhcpLines << "[ipv4]"
			nmDhcpLines << "method=auto"
			def dhcpDnsServers = primaryInterface?.dnsServers?.split(',')?.collect { it.trim() }?.findAll { it }
			if (dhcpDnsServers) {
				nmDhcpLines << "dns=${dhcpDnsServers.join(';')};"
				log.info("enhanceCloudInitConfig: OEL DHCP - adding DNS servers to NM keyfile: ${dhcpDnsServers}")
			}
			nmDhcpLines << ""
			nmDhcpLines << "[ipv6]"
			nmDhcpLines << "method=auto"

			def nmFileEntryLines = []
			nmFileEntryLines << "- path: /etc/NetworkManager/system-connections/morpheus-dhcp.nmconnection"
			nmFileEntryLines << "  content: |"
			nmDhcpLines.each { line -> nmFileEntryLines << "    ${line}" }
			nmFileEntryLines << "  permissions: '0600'"
			nmFileEntryLines << "  owner: root:root"
			writeFilesEntries << nmFileEntryLines.join('\n')

			// NM conf.d override: force dhclient as the DHCP backend.
			// NM's internal DHCP client does not set the BROADCAST flag in DHCP DISCOVER
			// packets by default. oVirt port security drops unicast DHCP OFFERs, so no lease
			// is ever obtained. dhclient sets the broadcast flag by default and works correctly.
			// This conf.d file is picked up by NM on restart in the runcmd below.
			def nmConfLines = []
			nmConfLines << "[main]"
			nmConfLines << "dhcp=dhclient"

			def nmConfFileEntryLines = []
			nmConfFileEntryLines << "- path: /etc/NetworkManager/conf.d/90-morpheus-dhcp.conf"
			nmConfFileEntryLines << "  content: |"
			nmConfLines.each { line -> nmConfFileEntryLines << "    ${line}" }
			nmConfFileEntryLines << "  permissions: '0644'"
			nmConfFileEntryLines << "  owner: root:root"
			writeFilesEntries << nmConfFileEntryLines.join('\n')

			// systemd-networkd .network file (for NM-less templates like minimal OEL 9 images):
			// RequestBroadcast=yes forces the client to request a broadcast reply,
			// which oVirt port security forwards correctly.
			// Name=en* catches enp1s0/ens3 etc.; Name=eth* catches eth0.
			// Type=ether restricts to physical ethernet only (not loopback or virtual).
			def networkdDhcpLines = []
			networkdDhcpLines << "[Match]"
			networkdDhcpLines << "Name=en*"
			networkdDhcpLines << "Name=eth*"
			networkdDhcpLines << "Type=ether"
			networkdDhcpLines << ""
			networkdDhcpLines << "[Network]"
			networkdDhcpLines << "DHCP=ipv4"
			networkdDhcpLines << ""
			networkdDhcpLines << "[DHCP]"
			networkdDhcpLines << "ClientIdentifier=mac"
			networkdDhcpLines << "RequestBroadcast=yes"

			def networkdFileEntryLines = []
			networkdFileEntryLines << "- path: /etc/systemd/network/01-morpheus-dhcp.network"
			networkdFileEntryLines << "  content: |"
			networkdDhcpLines.each { line -> networkdFileEntryLines << "    ${line}" }
			networkdFileEntryLines << "  permissions: '0644'"
			networkdFileEntryLines << "  owner: root:root"
			writeFilesEntries << networkdFileEntryLines.join('\n')

			// Runtime detection: use whichever networking stack is present.
			// For NM: restart NM so it picks up the dhclient conf.d override (broadcast fix),
			// remove the networkd file to avoid stack conflict, then bring up morpheus-dhcp.
			// Poll for IPv4 address (up to 30s) since nmcli connection up may return before
			// the DHCP lease is fully written to the interface.
			// For systemd-networkd: remove the NM files and restart networkd.
			runcmdEntries << "- |"
			runcmdEntries << "  if command -v nmcli >/dev/null 2>&1; then"
			runcmdEntries << "    rm -f /etc/systemd/network/01-morpheus-dhcp.network"
			runcmdEntries << "    nmcli connection up morpheus-dhcp 2>/dev/null || true"
			runcmdEntries << "    if ! ip -4 addr show scope global | grep -q inet; then"
			runcmdEntries << "      command -v dhclient >/dev/null 2>&1 || dnf install -y dhcp-client --setopt=install_weak_deps=False --nodocs -q 2>/dev/null || true"
			runcmdEntries << "      if command -v dhclient >/dev/null 2>&1; then"
			runcmdEntries << "        printf '[main]\\ndhcp=dhclient\\n' > /etc/NetworkManager/conf.d/90-morpheus-dhcp.conf"
			runcmdEntries << "      else"
			runcmdEntries << "        rm -f /etc/NetworkManager/conf.d/90-morpheus-dhcp.conf"
			runcmdEntries << "      fi"
			runcmdEntries << "      systemctl restart NetworkManager"
			runcmdEntries << "      nmcli connection up morpheus-dhcp 2>/dev/null || true"
			runcmdEntries << "    fi"
			runcmdEntries << "    for i in \$(seq 1 30); do"
			runcmdEntries << "      ip -4 addr show scope global | grep -q inet && break"
			runcmdEntries << "      sleep 1"
			runcmdEntries << "    done"
			runcmdEntries << "  elif systemctl is-active systemd-networkd >/dev/null 2>&1; then"
			runcmdEntries << "    rm -f /etc/NetworkManager/system-connections/morpheus-dhcp.nmconnection"
			runcmdEntries << "    rm -f /etc/NetworkManager/conf.d/90-morpheus-dhcp.conf"
			runcmdEntries << "    systemctl restart systemd-networkd"
			runcmdEntries << "    networkctl wait-online --timeout=30 2>/dev/null || true"
			runcmdEntries << "  fi"
			runcmdEntries << "  systemctl restart systemd-resolved 2>/dev/null || true"
			runcmdEntries << "  for i in \$(seq 1 30); do"
			runcmdEntries << "    grep -q '^nameserver' /run/systemd/resolve/resolv.conf 2>/dev/null && break"
			runcmdEntries << "    sleep 1"
			runcmdEntries << "  done"
			log.info("enhanceCloudInitConfig: OEL DHCP - writing NM keyfile, dhclient conf.d, and systemd-networkd .network file for runtime detection")
		}

		if (!writeFilesEntries && !runcmdEntries) {
			return cloudConfig
		}

		// Merge into existing write_files / runcmd sections, or append new sections.
		// This ensures we don't silently skip config when those sections already exist.
		if (writeFilesEntries) {
			def newEntries = "\n" + writeFilesEntries.join('\n')
			if (cloudConfig =~ /(?m)^write_files:\s*$/) {
				cloudConfig = cloudConfig.replaceFirst(/(?m)^write_files:\s*$/, java.util.regex.Matcher.quoteReplacement('write_files:' + newEntries))
			} else {
				cloudConfig += "\nwrite_files:" + newEntries
			}
		}

		if (runcmdEntries) {
			def newCmds = "\n" + runcmdEntries.join('\n')
			if (cloudConfig =~ /(?m)^runcmd:\s*$/) {
				cloudConfig = cloudConfig.replaceFirst(/(?m)^runcmd:\s*$/, java.util.regex.Matcher.quoteReplacement('runcmd:' + newCmds))
			} else {
				cloudConfig += "\n\nruncmd:" + newCmds
			}
		}

		log.debug("Enhanced cloud-init with ${writeFilesEntries.size()} write_files entries and ${runcmdEntries.size()} runcmd entries")
		log.debug("Enhanced cloud-init result length: ${cloudConfig?.length()}")
		return cloudConfig
	}

	protected void runVirtualMachine(Cloud cloud, Object workloadRequest, Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		try {
			def imageUploadResults = insertImage(cloud, workloadRequest, runConfig)
			log.info("imageUploadResults: ${imageUploadResults}")
			if(imageUploadResults.success == true && (imageUploadResults.imageId || imageUploadResults.imageType == 'iso')) {
				if(imageUploadResults.imageId) {
					// If we have an imageId, let's make sure Morpheus has a reference to this location
					// (NOTE: The call to create below will not duplicate the location if it already exists)
					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage: new VirtualImageIdentityProjection(id: runConfig.virtualImage.id),
						externalId  : imageUploadResults.imageId,
						imageRegion : runConfig.regionCode,
						imageFolder : runConfig.imageFolderName
					])
					morpheus.async.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()
				}
				runConfig.imageRef = imageUploadResults.imageId

				def runResults = insertVm(runConfig, provisionResponse, opts)

				if(provisionResponse.success) {
					finalizeVm(runConfig, provisionResponse, runResults)
				}
			} else {
				provisionResponse.setError(imageUploadResults.message)
			}
		} catch(e) {
			log.error("runVirtualMachine error:${e}", e)
			provisionResponse.setError('failed to upload image file')
		}
	}

	protected insertImage(Cloud cloud, Object workloadRequest, Map runConfig) {
		log.debug "insertImage: ${cloud} ${runConfig}"
		def taskResults = [success:false, imageId:runConfig.imageId, imageType: null]
		def lock
		VirtualImage virtualImage = runConfig.virtualImage
		try {
			log.debug("imageUploadId: ${runConfig.imageId}")

			if(!virtualImage && runConfig.virtualImageId) {
				try {
					virtualImage = morpheus.async.virtualImage.get(runConfig.virtualImageId).blockingGet()
				} catch(e) {
					log.debug "Error in get image: ${e}"
				}
			}
			if(runConfig.imageRef == null && virtualImage) {
				lock = morpheus.acquireLock("olvm.imageupload.${virtualImage.id}".toString(), [timeout:IMAGE_TIMEOUT, ttl:IMAGE_TTL]).blockingGet()
				log.debug "Uploading image ${virtualImage.id}"
				morpheus.async.process.startProcessStep(workloadRequest.process, new ProcessEvent(type: ProcessEvent.ProcessType.provisionImage), 'uploading').blockingGet()

				Collection<CloudFile> cloudFiles
				try {
					cloudFiles = morpheus.async.virtualImage.getVirtualImageFiles(virtualImage).blockingGet()
					log.debug "cloudfiles: ${cloudFiles?.size()}"
				} catch(e) {
					log.debug "error getVirtualImageFiles: ${e}"
				}
				CloudFile qcowFile = OlvmComputeUtility.findQcowFile(cloudFiles)
				def minDisk = virtualImage.minDisk ? virtualImage.minDisk.div(ComputeUtility.ONE_KILOBYTE) : DEFAULT_MIN_DISK
				def minRam = virtualImage.minRam ?: DEFAULT_MIN_RAM
				def containerImage = [
					imageSrc     : qcowFile?.getURL(),
					minDisk      : minDisk,
					minRam       : minRam,
					tags         : 'morpheus',
					imageType    : ImageType.qcow2,
					containerType: ImageType.qcow2,
					imageFile    : qcowFile,
					cloudFiles   : cloudFiles,
					name         : virtualImage.name,
					virtualImage : virtualImage
				]
				def imageConfig = [
					hostId            : runConfig.hostId,
					datastoreId       : runConfig.imageDatastoreId ?: runConfig.datastoreId,
					storageDomainId	  : runConfig.rootVolume.datastore.externalId,
					cloud             : cloud,
					image             : containerImage,
					resourcePool      : runConfig.resourcePoolId,
					cluster           : runConfig.cluster,
					clusterRef   	  : runConfig.clusterRef,
					datacenter        : runConfig.datacenter,
					datacenterRef	  : runConfig.datacenterRef,
					networkId         : runConfig.networkId,
					networkBackingType: runConfig.networkBackingType,
					folder            : runConfig.imageFolderExtId,
					connection  	  : runConfig.connection,
					proxySettings     : workloadRequest.proxyConfiguration
				]

				def imageResults = OlvmComputeUtility.insertImage(imageConfig)
				log.debug("insertContainerImage imageResults: ${imageResults}")
				if(imageResults.success == true) {
					taskResults.imageId = imageResults.data.imageRef
					VirtualImageLocation virtualImageLocation = new VirtualImageLocation([
						virtualImage: new VirtualImageIdentityProjection(id: runConfig.virtualImage.id),
						externalId: taskResults.imageId,
						imageRegion: runConfig.regionCode,
						imageFolder: runConfig.imageFolderName
					])
					morpheus.async.virtualImage.location.create([virtualImageLocation], cloud).blockingGet()
					taskResults.success = true

					// end process step
					morpheus.async.process.endProcessStep(workloadRequest.process, 'complete', 'complete').blockingGet()
				}
			} else if(virtualImage?.imageType == ImageType.iso) {
				log.debug "No upload required for ${virtualImage}... iso image"
				taskResults.imageType = 'iso'
				taskResults.success = true
			} else {
				log.debug "No upload required for ${virtualImage}"
				taskResults.success = true
				taskResults.imageId = runConfig.imageRef
			}
			log.debug("imageUploadTask: ${taskResults}")
		}
		catch(imageException) {
			log.error("imageException: ${imageException}", imageException)
			taskResults.message = 'Error uploading image'
		}
		finally {
			if(lock) {
				morpheus.releaseLock("olvm.imageupload.${virtualImage.id}".toString(), [lock:lock]).blockingGet()
			}
		}
		return taskResults
	}

	protected insertVm(Map runConfig, ProvisionResponse provisionResponse, Map opts) {
		log.debug("insertVm runConfig: {}", runConfig)
		def taskResults = [success:false]
		try {
			ComputeServer server = runConfig.server
			Account account = server.account
			opts.createUserList = runConfig.userConfig.createUsers

			//save server
			runConfig.server = saveAndGet(server)

			//set install agent
			runConfig.installAgent = runConfig.noAgent && server.cloud.agentMode != 'cloudInit'
			log.debug("insertVm: noAgent=${runConfig.noAgent}, cloud.agentMode=${server.cloud.agentMode}, installAgent=${runConfig.installAgent}")

			//data volumes
			if (runConfig.dataDisks)
				runConfig.diskList = buildDataDiskList(runConfig.dataDisks)
			def createResults

			def backupSetId = opts.backupSetId
			def cloneContainerId = opts.cloneContainerId
			if(backupSetId && cloneContainerId) {
				Map rootSnapshot
				def snapshot = new OlvmSnapshotBackupProvider(plugin, morpheus).getSnapshotsForBackupResult(backupSetId, cloneContainerId)
				log.debug("Snapshots: ${snapshot}")
				if (snapshot) {
					runConfig.restoreSnapshot = snapshot
					createResults = OlvmComputeUtility.createServerFromSnapshot(runConfig)
				}
			}
			else {
				log.debug("insertVm: creating VM from template imageRef=${runConfig.imageRef}, name=${runConfig.name}, cluster=${runConfig.clusterRef}")
				createResults = OlvmComputeUtility.createServer(runConfig)
			}
			log.debug("create server: ${createResults}")
			if (createResults.success == true && createResults.data.vmId) {
				server.externalId = createResults.data.vmId
				provisionResponse.externalId = server.externalId
				log.debug("insertVm: VM created with externalId=${server.externalId}, waiting for VM to exist in OLVM")
				server = saveAndGet(server)
				runConfig.server = server

				OlvmComputeUtility.waitForServerExists([connection:runConfig.connection, server:server])
				log.debug("insertVm: VM ${server.externalId} is now ready (status=down)")

				// once server exists set entity ids onto our model
				def vmDetails = OlvmComputeUtility.getServerDetail([connection: runConfig.connection, server: server])
				log.debug("insertVm: vmDetails success=${vmDetails.success}, status=${vmDetails.data?.status}, disks=${vmDetails.data?.disks?.size()}, nics=${vmDetails.data?.nics?.size()}")

				// change size of the of the root volume and save off id
				def rootVolume = server.volumes.find { it -> return it.rootVolume }
				rootVolume.externalId = vmDetails.data.disks.find { d -> return d.bootable == true }.id
				log.debug("insertVm: root volume externalId=${rootVolume.externalId}, maxStorage=${rootVolume.maxStorage}")
				rootVolume = saveAndGetVolume(rootVolume)
				def response = OlvmComputeUtility.updateDiskSize([
					connection: runConfig.connection, disk: [id: rootVolume.externalId, size: rootVolume.maxStorage]
				])
				log.debug("insertVm: updateDiskSize success=${response.success}")

				if (!response.success) {
					taskResults.message = "Unable to update disk size: ${OlvmComputeUtility.extractErrorMessage(response.data)}"
					return taskResults
				}

			// Handle data disks — some may already exist on the VM (cloned from template via disk_attachments),
			// others are truly extra disks that need to be created fresh via the API
			if (runConfig.dataDisks?.size() > 0) {
				log.debug("insertVm: handling ${runConfig.dataDisks.size()} data disk(s), ${vmDetails.data.disks.findAll{!it.bootable}.size()} cloned from template")
				def clonedTemplateDisks = vmDetails.data.disks.findAll { d -> !d.bootable }
				def extraDataDisks = []

				runConfig.dataDisks.eachWithIndex { StorageVolume vol, int i ->
					if (i < clonedTemplateDisks.size()) {
						// This data disk was already cloned from the template — just assign its external id
						vol.externalId = clonedTemplateDisks[i].id
						log.debug("insertVm: data disk[${i}] '${vol.name}' cloned from template, externalId=${vol.externalId}")
						saveAndGetVolume(vol)
					} else {
						// This is an extra disk beyond the template — needs to be created via API
						log.debug("insertVm: data disk[${i}] '${vol.name}' is extra, will be created via API")
						extraDataDisks << vol
					}
				}

				if (extraDataDisks) {
					log.debug("insertVm: adding ${extraDataDisks.size()} extra data disk(s) via API")
					def dataDiskResp = OlvmComputeUtility.addDisksToVm([
						connection: runConfig.connection, vmId: server.externalId,
						disks     : extraDataDisks
					])
					log.debug("insertVm: addDisksToVm success=${dataDiskResp.success}")

					for (StorageVolume vol in extraDataDisks) {
						def cloudDisk = dataDiskResp.data.disks.find { it -> return it.name == vol.name }
						vol.externalId = cloudDisk.externalId
						saveAndGetVolume(vol)
					}
				}
			}


				if (!vmDetails.data.nics) {
					// hostRequest.networkConfiguration.primaryInterface can be null for auto-provisioned
					// host nodes (e.g. K8s worker nodes) even though the network was already resolved
					// into runConfig.networkRef in buildRunConfig - fall back to that instead of NPEing.
					def primaryInterface = runConfig.networkConfig?.primaryInterface
					if (!primaryInterface && runConfig.networkRef) {
						primaryInterface = [name: 'eth0', network: [externalId: runConfig.networkRef]]
						log.debug("insertVm: networkConfig.primaryInterface missing, falling back to resolved networkRef=${runConfig.networkRef}")
					}
					if (primaryInterface) {
						log.debug("insertVm: VM has no NICs from template, adding primary NIC for network=${primaryInterface.network?.externalId ?: primaryInterface.network?.id}")
						def addPrimaryInterface = OlvmComputeUtility.addNicsToVm(
							[connection: runConfig.connection, nics: [primaryInterface], vmId: server.externalId]
						)
						log.debug("insertVm: addPrimaryInterface success=${addPrimaryInterface.success}")
						//saveAndGetNic(addPrimaryInterface.data?.first())
					} else {
						log.warn("insertVm: unable to add primary NIC for server ${server.id} - no primaryInterface or resolved networkRef available")
					}
				} else {
					log.debug("insertVm: VM already has ${vmDetails.data.nics.size()} NIC(s) from template")
				}

				// add extra interfaces to vm
				if (runConfig.networkConfig.extraInterfaces) {
					log.debug("insertVm: adding ${runConfig.networkConfig.extraInterfaces.size()} extra interface(s)")
					def addResp = OlvmComputeUtility.addNicsToVm([
						connection: runConfig.connection, nics: runConfig.networkConfig.extraInterfaces, vmId: server.externalId
					])
					log.debug("insertVm: addExtraInterfaces success=${addResp.success}")

					for (nic in addResp.data) {
						saveAndGetNic(nic)
					}
				}

				// start vm for the first time
				//log.debug("insertVm - Starting VM with cloud-init. Cloud-init content: ${runConfig.cloudConfig}")
				//OlvmComputeUtility.startVmWithCloudInit([connection: runConfig.connection, server: server, cloudInitScript: runConfig.cloudConfig])

				// start vm for the first time
				// Add network configuration to cloud-init if static IP is configured
				// def cloudInitScript = runConfig.cloudConfig
				// if (cloudInitScript && runConfig.networkConfig) {
				// 	def networkConfigYaml = buildCloudInitNetworkConfig(runConfig.networkConfig)
				// 	if (networkConfigYaml) {
				// 		cloudInitScript += networkConfigYaml
				// 		log.debug("insertVm - Added network config to cloud-init")
				// 	}
				// }
				// log.debug("insertVm - Starting VM with cloud-init. Cloud-init content: ${cloudInitScript}")
				// OlvmComputeUtility.startVmWithCloudInit([connection: runConfig.connection, server: server, cloudInitScript: cloudInitScript])
				// Enhance cloud-init with hostname and network configuration
				log.debug("insertVm - Enhancing cloud-init with hostname and network config")
				def hasNetworkBeforeEnhance = runConfig.cloudConfig?.contains('network:')
				def enhancedCloudConfig = enhanceCloudInitConfig(
					runConfig.cloudConfig,
					runConfig.hostname,
					runConfig.domainName,
					runConfig.networkConfig,
					runConfig.serverOs,
					runConfig.virtualImage
				)

				log.debug("insertVm - Starting VM with cloud-init only (no OLVM initialization)")
				log.debug("insertVm - cloud-init script length: ${enhancedCloudConfig?.length() ?: 0} chars")
				log.info("insertVm - cloud-init config length: ${enhancedCloudConfig?.length()}")
				log.info("insertVm - network section: present before enhance=${hasNetworkBeforeEnhance}, present after=${enhancedCloudConfig?.contains('network:')}")
				log.info("insertVm - cloudConfigNetwork (from core): ${runConfig.cloudConfigNetwork ?: '(empty)'}")
				log.info("insertVm - full cloud-config:\n${enhancedCloudConfig?.take(3000) ?: '(empty)'}")
				// Start VM with cloud-init only - no OLVM initialization parameters
				def startResult = OlvmComputeUtility.startVmWithCloudInit([
					connection: runConfig.connection,
					server: server,
					cloudInitScript: enhancedCloudConfig,
					cloudConfigNetwork: runConfig.cloudConfigNetwork ?: null
				])
				log.debug("insertVm: startVmWithCloudInit success=${startResult?.success}, msg=${startResult?.msg}")
				if (!startResult?.success) {
					def startError = startResult?.error ?: startResult?.msg ?: 'unknown error'
					log.error("insertVm: VM failed to start: ${startError}")
					taskResults.message = "Failed to start VM: ${startError}"
					provisionResponse.setError(taskResults.message)
					return taskResults
				}
				// wait for ready
				log.debug("insertVm: waiting for server ready (status=up)")
				def statusResults = OlvmComputeUtility.checkServerReady(runConfig)
				log.info("insertVm: checkServerReady success=${statusResults.success}, status=${statusResults.data?.status}, ipV4=${statusResults.data?.ipV4}")
				if (statusResults.success == true) {
					//good to go
					def serverDetails = statusResults.data
					log.debug("server details: {}", serverDetails)
					//update network info
					// Prefer IP reported by OLVM guest agent; fall back to interface IP (static) or internalIp
					def privateIp = serverDetails.ipV4?.find { it } ?:
						runConfig.networkConfig?.primaryInterface?.ipAddress ?:
						server.interfaces?.find { it.primaryInterface }?.ipAddress ?:
						server.internalIp
					def publicIp = privateIp
					log.info("insertVm: resolved IP - privateIp=${privateIp} (olvmGuestAgent=${serverDetails.ipV4}, staticInterfaceIp=${runConfig.networkConfig?.primaryInterface?.ipAddress})")

					taskResults.sshHost = publicIp
					taskResults.server = serverDetails
					taskResults.success = true
					provisionResponse.success = true
				} else {
					log.warn("insertVm: checkServerReady timed out - VM did not reach 'up' status within the wait window")
					taskResults.message = 'Failed to get server status'
					provisionResponse.setError(taskResults.message)
				}
			} else {
				log.warn("insertVm: createServer failed - success=${createResults.success}, vmId=${createResults.data?.vmId}, msg=${createResults.msg}")
				taskResults.message = createResults.msg
				provisionResponse.setError(taskResults.message ?: 'Failed to create VM')
			}
		}
		catch (Throwable t) {
			log.error("Unable to insertVm(): ${t.message}", t)
			taskResults.success = false
			taskResults.error = "Unable to insertVm(): ${t.message}"
			provisionResponse.success = false
			provisionResponse.error = taskResults.error
		}
		return taskResults
	}

	def buildDataDiskList(dataDisks) {
		def rtn = []
		if(dataDisks) {
			dataDisks?.eachWithIndex { dataVolume, index ->
				def deviceName = OlvmComputeUtility.getDeviceName(index+1)
				rtn << [diskType:dataVolume?.type?.name ?: 'standard', diskSize: dataVolume.maxStorage.div(ComputeUtility.ONE_GIGABYTE),
						deviceName:deviceName]
				dataVolume.deviceName = deviceName
			}
		}
		return rtn
	}

	protected Long getImageId(imageId) {
		Long rtn
		try {
			rtn = imageId?.toLong()
		} catch(e) {
			//nothing
		}
		return rtn
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheus.async.computeServer.save([server]).blockingGet()
		if(!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}" )
		}
		return morpheus.async.computeServer.get(server.id).blockingGet()
	}

	protected StorageVolume saveAndGetVolume(StorageVolume volume) {
		def saveSuccessful = morpheus.async.storageVolume.save(volume).blockingGet()
		if (!saveSuccessful)
			log.warn("Error saving storage volume: ${volume.id}")
		return morpheus.async.storageVolume.get(volume.id).blockingGet()
	}

	protected ComputeServerInterface saveAndGetNic(ComputeServerInterface nic) {
		def saveSuccessful = morpheus.async.computeServer.computeServerInterface.save(nic).blockingGet()
		if (!saveSuccessful)
			log.warn("Error saving network interface: ${nic.internalId}")
		return morpheus.async.computeServer.computeServerInterface.get(nic.id).blockingGet()
	}
}
