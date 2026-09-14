package com.morpheus.olvm

import com.morpheus.olvm.sync.ClusterSync
import com.morpheus.olvm.sync.DatacenterSync
import com.morpheus.olvm.sync.HostSync
import com.morpheus.olvm.sync.NetworkSync
import com.morpheus.olvm.sync.StorageDomainSync
import com.morpheus.olvm.sync.TemplateSync
import com.morpheus.olvm.sync.VirtualMachineSync
import com.morpheus.olvm.util.OlvmComputeUtility
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudFolder
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.PlatformType
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class OlvmCloudProvider implements CloudProvider {
	public static final String CLOUD_PROVIDER_CODE = 'olvm'

	protected MorpheusContext context
	protected Plugin plugin

	public OlvmCloudProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.@plugin = plugin
		this.@context = ctx
	}

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return 'This plugin integrates Oracle Linux Virtualization Manager into the morpheus ecosystem'
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:'OLVM-140.svg', darkPath:'OLVM-140.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		return new Icon(path:'olvm-symbolonly.svg', darkPath:'olvm-symbolonly.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> options = []
		options << new OptionType(
			name:'API Url',
			code:'olvm.plugin.apiUrl',
			displayOrder:0,
			fieldContext:'domain',
			fieldLabel:'API URL',
			fieldCode: 'gomorpheus.optiontype.ApiUrl',
			fieldName: 'serviceUrl',
			inputType: OptionType.InputType.TEXT,
			required: true
		)
		options << new OptionType(
			name: 'Credentials',
			code: 'olvm.plugin.credential',
			inputType: OptionType.InputType.CREDENTIAL,
			fieldName: 'type',
			fieldLabel: 'Credentials',
			fieldContext: 'credential',
			required: true,
			defaultValue: 'local',
			displayOrder: 10,
			optionSource: 'credentials',
			config: '{"credentialTypes":["username-password"]}'
		)
		options << new OptionType(
			name:'Username',
			code:'olvm.plugin.username',
			fieldName:'serviceUsername',
			displayOrder:12,
			fieldLabel:'Username',
			required:true,
			inputType:OptionType.InputType.TEXT,
			fieldContext:'domain',
			localCredential:true
		)
		options << new OptionType(
			name:'Password',
			code:'olvm.plugin.password',
			fieldName:'servicePassword',
			displayOrder:13,
			fieldLabel:'Password',
			required:true,
			inputType:OptionType.InputType.PASSWORD,
			fieldContext:'domain',
			localCredential:true
		)
		options << new OptionType(
			name:'Datacenter',
			code:'olvm-plugin-datacenter',
			displayOrder:40,
			fieldContext:'config',
			fieldLabel:'Datacenter',
			fieldCode:'gomorpheus.optiontype.datacenter',
			fieldName:'datacenter',
			inputType: OptionType.InputType.SELECT,
			optionSource:'olvmCloudDatacenters',
			noBlank:true,
			dependsOnCode:'zone.serviceUrl, zone.serviceUsername, zone.servicePassword, credential.type, credential.username, credential.password'
		)
		options << new OptionType(
			name:'Inventory Existing Instances',
			code:'olvm.plugin.importExisting',
			fieldName:'importExisting',
			displayOrder:90,
			fieldLabel:'Inventory Existing Instances',
			required:false,
			inputType:OptionType.InputType.CHECKBOX,
			fieldContext:'config'
		)
		options << new OptionType(
			name:'Enable SSL Verification',
			code:'olvm.plugin.enableSslVerification',
			fieldName:'enableSslVerification',
			displayOrder:95,
			fieldLabel:'Enable SSL Verification',
			required:false,
			inputType:OptionType.InputType.CHECKBOX,
			fieldContext:'config',
			helpText:'Verify the TLS certificate presented by the OLVM engine. Leave disabled to accept self-signed or otherwise untrusted certificates.'
		)

		return options
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
	    return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * Grabs available backup providers related to the target Cloud Plugin.
	 * @return Collection of BackupProvider
	 */
	@Override
	Collection<BackupProvider> getAvailableBackupProviders() {
		Collection<BackupProvider> providers = []
		return providers
	}

	/**
	 * Provides a Collection of {@link NetworkType} related to this CloudProvider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		Collection<NetworkType> networks = []
		networks << new NetworkType(
			code:'olvm-logical-network',
			name:'OLVM Logical Network',
			overlay:false,
			creatable:false,
			nameEditable:false,
			cidrEditable:true,
			dhcpServerEditable:true,
			dnsEditable:true,
			gatewayEditable:true,
			ipv6Editable:true,
			vlanIdEditable:true,
			cidrRequired:true,
			canAssignPool:true,
			deletable:false,
			hasNetworkServer:false,
			hasCidr:true,
			optionTypes:[]
		)
		networks << new NetworkType(
			code:'olvm-vnic-profile',
			name:'OLVM VNIC Profile',
			overlay:false,
			creatable:false,
			nameEditable:false,
			cidrEditable:true,
			dhcpServerEditable:true,
			dnsEditable:true,
			gatewayEditable:true,
			ipv6Editable:true,
			vlanIdEditable:true,
			cidrRequired:true,
			canAssignPool:true,
			deletable:false,
			hasNetworkServer:false,
			hasCidr:true,
			optionTypes:[]
		)
		return networks
	}

	/**
	 * Provides a Collection of {@link NetworkSubnetType} related to this CloudProvider
	 * @return Collection of NetworkSubnetType
	 */
	@Override
	Collection<NetworkSubnetType> getSubnetTypes() {
		Collection<NetworkSubnetType> subnets = []
		return subnets
	}

	/**
	 * Provides a Collection of {@link StorageVolumeType} related to this CloudProvider
	 * @return Collection of StorageVolumeType
	 */
	@Override
	Collection<StorageVolumeType> getStorageVolumeTypes() {
		def volumeTypes = []

		volumeTypes << new StorageVolumeType([
			code        : 'olvm-standard', displayName: 'standard', name: 'olvm-standard',
			description : 'OLVM - standard', volumeType: 'volume', enabled: true,
			customLabel : true, customSize: true, defaultType: true, autoDelete: true,
			minStorage  : (ComputeUtility.ONE_GIGABYTE), maxStorage: (16L * ComputeUtility.ONE_TERABYTE),
			hasDatastore: true, allowSearch: true, volumeCategory: 'volume',
			displayOrder: 0
		])
	}

	/**
	 * Provides a Collection of {@link StorageControllerType} related to this CloudProvider
	 * @return Collection of StorageControllerType
	 */
	@Override
	Collection<StorageControllerType> getStorageControllerTypes() {
		Collection<StorageControllerType> controllerTypes = []
		return controllerTypes
	}

	/**
	 * Grabs all {@link ComputeServerType} objects that this CloudProvider can represent during a sync or during a provision.
	 * @return collection of ComputeServerType
	 */
	@Override
	Collection<ComputeServerType> getComputeServerTypes() {
		Collection<ComputeServerType> serverTypes = []
		serverTypes << new ComputeServerType(
			name:'OLVM Instance',
			code:'olvmUnmanaged',
			description:'An unmanaged OLVM vm',
			managed:false,
			reconfigureSupported:false,
			hasAutomation:false,
			supportsConsoleKeymap:false,
			platform:PlatformType.none,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			guestVm:true
		)
		serverTypes <<  new ComputeServerType(
			name:'OLVM Instance',
			code:'olvmVm',
			nodeType:'morpheus-vm-node',
			description:'An OLVM virtual machine',
			reconfigureSupported:true,
			hasAutomation:true,
			supportsConsoleKeymap:false,
			platform:PlatformType.linux,
			managed:true,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			guestVm:true
		)
		serverTypes << new ComputeServerType(
			name:'OLVM Windows Instance',
			code:'olvmWindowsVm',
			nodeType:'morpheus-windows-vm-node',
			description:'An OLVM windows virtual machine',
			reconfigureSupported:true,
			hasAutomation:true,
			supportsConsoleKeymap:false,
			platform:PlatformType.windows,
			managed:true,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			guestVm:true
		)
		serverTypes << new ComputeServerType(
			name:'OLVM Hypervisor',
			code:'olvm-hypervisor',
			nodeType:'olvm-hypervisor',
			description:'An OLVM KVM Host',
			reconfigureSupported:false,
			hasAutomation:false,
			enabled:true,
			selectable:false,
			externalDelete:false,
			managed:false,
			controlPower:false,
			controlSuspend:false,
			creatable:false,
			containerHypervisor:false,
			bareMetalHost:false,
			vmHypervisor:true,
			agentType:null,
			platform:PlatformType.linux,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			guestVm:false
		)
		serverTypes << new ComputeServerType(
			name:'OLVM Kubernetes Master',
			code:'olvm-kube-master',
			description:'OLVM Kubernetes Master',
			containerHypervisor:true,
			reconfigureSupported:true,
			hasAutomation:true,
			supportsConsoleKeymap:true,
			platform:PlatformType.linux,
			managed:true,
			hasMaintenanceMode: true,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			agentType:ComputeServerType.AgentType.host,
			clusterType:ComputeServerType.ClusterType.kubernetes,
			computeTypeCode:'kube-master',
			nodeType:'kube-master'
		)
		serverTypes << new ComputeServerType(
			name:'OLVM Kubernetes Worker',
			code:'olvm-kube-worker',
			description:'OLVM Kubernetes Worker',
			containerHypervisor:true,
			reconfigureSupported:true,
			hasAutomation:true,
			hasMaintenanceMode: true,
			supportsConsoleKeymap:true,
			platform:PlatformType.linux,
			managed:true,
			provisionTypeCode:OlvmProvisionProvider.PROVISION_PROVIDER_CODE,
			agentType:ComputeServerType.AgentType.host,
			clusterType:ComputeServerType.ClusterType.kubernetes,
			computeTypeCode:'kube-worker',
			nodeType:'kube-worker'
		)

		return serverTypes
	}

	/**
	 * Validates the submitted cloud information to make sure it is functioning correctly.
	 * If a {@link ServiceResponse} is not marked as successful then the validation results will be
	 * bubbled up to the user.
	 * @param cloudInfo cloud
	 * @param validateCloudRequest Additional validation information
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
		log.info("validate: {}", cloudInfo)
		try {
			if(cloudInfo) {
				def config = cloudInfo.getConfigMap()
				def username, password

				cloudInfo.serviceUrl = validateCloudRequest.opts?.zone?.serviceUrl ?: cloudInfo.serviceUrl

				if(validateCloudRequest.credentialType == 'local') {
					username = validateCloudRequest.opts?.zone?.serviceUsername ?: cloudInfo.serviceUsername
					if(validateCloudRequest.opts?.zone?.servicePassword && validateCloudRequest.opts?.zone?.servicePassword != '************') {
						password = validateCloudRequest.opts?.zone?.servicePassword
					} else {
						password = cloudInfo.servicePassword
					}

					if(!username) {
						return new ServiceResponse(success: false, msg: 'Enter a username', errors: ['serviceUsername': 'Required field'])
					}
					if(!password) {
						return new ServiceResponse(success: false, msg: 'Enter a password', errors: ['servicePassword': 'Required field'])
					}
				}
				else {
					username = validateCloudRequest.credentialUsername
					password = validateCloudRequest.credentialPassword

					if (!username) {
						return new ServiceResponse(success: false, msg: 'Enter a username', errors: ['credential.username': 'Required field'])
					}
					if (!password) {
						return new ServiceResponse(success: false, msg: 'Enter a password', errors: ['credential.password': 'Required field'])
					}
				}

				//test creds
				cloudInfo.accountCredentialData = [username: username, password: password]
				cloudInfo.accountCredentialLoaded = true
				def testResults = OlvmComputeUtility.testConnection(cloudInfo)
				if(!testResults.success) {
					if (testResults.invalidLogin) {
						return new ServiceResponse(success: false, msg: 'Invalid olvm credentials')
					} else {
						return new ServiceResponse(success: false, msg: 'Unknown error connecting to olvm')
					}
				}
				return ServiceResponse.success()
			} else {
				return new ServiceResponse(success: false, msg: 'No cloud found')
			}
		} catch(e) {
			log.error('Error validating cloud', e)
			return new ServiceResponse(success: false, msg: 'Error validating cloud')
		}
	}

	/**
	 * Called when a Cloud From Morpheus is first saved. This is a hook provided to take care of initial state
	 * assignment that may need to take place.
	 * @param cloudInfo instance of the cloud object that is being initialized.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse initializeCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc.
	 * @param cloudInfo cloud
	 * @return ServiceResponse. If ServiceResponse.success == true, then Cloud status will be set to Cloud.Status.ok. If
	 * ServiceResponse.success == false, the Cloud status will be set to ServiceResponse.data['status'] or Cloud.Status.error
	 * if not specified. So, to indicate that the Cloud is offline, return `ServiceResponse.error('cloud is not reachable', null, [status: Cloud.Status.offline])`
	 */
	@Override
	ServiceResponse refresh(Cloud cloudInfo) {
		OlvmVersion.setMDC()
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			def testResults = OlvmComputeUtility.testConnection(cloudInfo)
			if(testResults.success) {
				def connection = testResults.data.connection
				def now = new Date().time
				new DatacenterSync(this.plugin, this.morpheus, cloudInfo, connection).execute()
				log.info("${cloudInfo.name}: Datacenters Synced in ${new Date().time - now}ms")
				now = new Date().time
				new ClusterSync(this.plugin, this.morpheus, cloudInfo, connection).execute()
				log.info("${cloudInfo.name}: Clusters Synced in ${new Date().time - now}ms")
				now = new Date().time
				new StorageDomainSync(this.plugin, this.morpheus, cloudInfo, connection).execute()
				log.info("${cloudInfo.name}: StorageDomains Synced in ${new Date().time - now}ms")
				now = new Date().time
				new NetworkSync(this.plugin, this.morpheus, cloudInfo, connection).execute()
				log.info("${cloudInfo.name}: Networks Synced in ${new Date().time - now}ms")
				now = new Date().time
				List<String> clusterIds = null
				if(cloudInfo.configMap.datacenter && cloudInfo.configMap.datacenter?.toString() != 'all') {
					clusterIds = context.services.cloud.pool.listIdentityProjections(
							new DataQuery().withFilters(
									new DataFilter<String>('type', 'cluster'),
									new DataFilter<String>('refType', 'ComputeZone'),
									new DataFilter<String>('refId', cloudInfo.id)
							)
					).collect { it.externalId.toString() }
				}
				new HostSync(this.plugin, this.morpheus, cloudInfo, connection, clusterIds).execute()
				log.info("${cloudInfo.name}: Hosts Synced in ${new Date().time - now}ms")
				now = new Date().time
				new TemplateSync(this.plugin, this.morpheus, cloudInfo, connection, clusterIds).execute()
				log.info("${cloudInfo.name}: Templates Synced in ${new Date().time - now}ms")
				now = new Date().time
				new VirtualMachineSync(this.plugin, this.morpheus, cloudInfo, connection, clusterIds).execute()
				log.info("${cloudInfo.name}: Virtual Machines Synced in ${new Date().time - now}ms")
				rtn.success = true
			}
			else {
				def status = testResults.data.invalidLogin ? 'invalid credentials' : 'error connecting'
				rtn = ServiceResponse.error(status)
				rtn.msg = status
			}
		 }
		catch (Throwable t) {
			log.error("refresh cloud error: ${t.message}", t)
		}
		return rtn
	}

	/**
	 * Zones/Clouds are refreshed periodically by the Morpheus Environment. This includes things like caching of brownfield
	 * environments and resources such as Networks, Datastores, Resource Pools, etc. This represents the long term sync method that happens
	 * daily instead of every 5-10 minute cycle
	 * @param cloudInfo cloud
	 */
	@Override
	void refreshDaily(Cloud cloudInfo) {
	}

	/**
	 * Called when a Cloud From Morpheus is removed. This is a hook provided to take care of cleaning up any state.
	 * @param cloudInfo instance of the cloud object that is being removed.
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteCloud(Cloud cloudInfo) {
		return ServiceResponse.success()
	}

	/**
	 * Returns whether the cloud supports {@link CloudPool}
	 * @return Boolean
	 */
	@Override
	Boolean hasComputeZonePools() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Network}
	 * @return Boolean
	 */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/**
	 * Returns whether a cloud supports {@link CloudFolder}
	 * @return Boolean
	 */
	@Override
	Boolean hasFolders() {
		return false
	}

	/**
	 * Returns whether a cloud supports {@link Datastore}
	 * @return Boolean
	 */
	@Override
	Boolean hasDatastores() {
		return true
	}

	/**
	 * Returns whether a cloud supports bare metal VMs
	 * @return Boolean
	 */
	@Override
	Boolean hasBareMetal() {
		return false
	}

	/**
	 * Indicates if the cloud supports cloud-init. Returning true will allow configuration of the Cloud
	 * to allow installing the agent remotely via SSH /WinRM or via Cloud Init
	 * @return Boolean
	 */
	@Override
	Boolean hasCloudInit() {
		return true
	}

	/**
	 * Indicates if the cloud supports the distributed worker functionality
	 * @return Boolean
	 */
	@Override
	Boolean supportsDistributedWorker() {
		return false
	}

	@Override
	Boolean provisionRequiresResourcePool() {
		return false
	}

	/**
	 * Called when a server should be started. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'on', and related instances set to 'running'
	 * @param computeServer server to start
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		def result = OlvmComputeUtility.startVm([cloud:computeServer.cloud, vmId:computeServer.externalId, morpheusCtx:morpheus])
		return new ServiceResponse(result.success, result.msg, null, null)
	}

	/**
	 * Called when a server should be stopped. Returning a response of success will cause corresponding updates to usage
	 * records, result in the powerState of the computeServer to be set to 'off', and related instances set to 'stopped'
	 * @param computeServer server to stop
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		def result = OlvmComputeUtility.stopVm([cloud:computeServer.cloud, vmId:computeServer.externalId, morpheusCtx:morpheus])
		return new ServiceResponse(result.success, result.msg, null, null)
	}

	/**
	 * Called when a server should be deleted from the Cloud.
	 * @param computeServer server to delete
	 * @return ServiceResponse
	 */
	@Override
	ServiceResponse deleteServer(ComputeServer computeServer) {
		return getProvisionProvider(getDefaultProvisionTypeCode()).deleteServer(computeServer)
	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
		return getAvailableProvisionProviders().find { it.code == providerCode }
	}

	/**
	 * Returns the default provision code for fetching a {@link ProvisionProvider} for this cloud.
	 * This is only really necessary if the provision type code is the exact same as the cloud code.
	 * @return the provision provider code
	 */
	@Override
	String getDefaultProvisionTypeCode() {
		return OlvmProvisionProvider.PROVISION_PROVIDER_CODE
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
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
		return CLOUD_PROVIDER_CODE
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

	@Override
	CloudClassification getCloudClassification() {
		return CloudClassification.PRIVATE;
	}
}
