package com.morpheus.olvm.util

import com.bertramlabs.plugins.karman.CloudFile
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.core.util.image.Qcow2InputStream
import com.morpheusdata.model.Cloud
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

@Slf4j
class OlvmComputeUtility {
    public static final Long DEFAULT_WAIT_TIMEOUT = 30l * 60l // 30 minute default

    static listDatacenters(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def resp = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/datacenters',
                reqOptions,
                'GET'
            )
            if (resp.success) {
                rtn.data = [
                    connection:connection,
                    datacenters:resp.data['data_center']
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get datacenters: ${extractErrorMessage(resp.data)}")
                rtn.error = "Unable to get datacenters: ${extractErrorMessage(resp.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get datacenters: ${t.message}", t)
            rtn.error = "Unable to get datacenters: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static listClusters(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            def connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def path = '/ovirt-engine/api/clusters'
            if(opts.datacenterId) {
                path = "/ovirt-engine/api/datacenters/${opts.datacenterId}/clusters".toString()
            }
            def response = client.callJsonApi(
                connection.apiUrl,
                path,
                reqOptions,
                'GET'
            )

            if (response.success) {
                rtn.data = [
                    connection:connection,
                    clusters:response.data.cluster
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get clusters: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get clusters: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get clusters: ${t.message}", t)
            rtn.error = "Unable to get clusters: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    // Fetches a single cluster's detail (including its data_center reference) by OLVM external id.
    static getCluster(opts) {
        def rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/clusters/${opts.clusterId}".toString(),
                reqOptions,
                'GET'
            )
            if (response.success) {
                rtn.data = response.data
                rtn.success = true
            }
            else {
                log.error("Unable to get cluster ${opts.clusterId}: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get cluster ${opts.clusterId}: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get cluster ${opts.clusterId}: ${t.message}", t)
            rtn.error = "Unable to get cluster ${opts.clusterId}: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static listTemplates(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/templates',
                reqOptions,
                'GET'
            )

            if (response.success) {
                rtn.data = [
                    connection: connection,
                    templates : response.data['template']
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get templates: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get templates: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get templates: ${t.message}", t)
            rtn.error = "Unable to get templates: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static listVirtualMachines(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/vms',
                reqOptions,
                'GET'
            )

            if (response.success) {
                rtn.data = [
                    connection: connection,
                    vms: response.data['vm'] ?: []
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get virtual machines: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get virtual machines: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get virtual machines: ${t.message}", t)
            rtn.error = "Unable to get virtual machines: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static listHosts(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)

			def queryParams = null
			if(opts.includeNetworkAttachments) {
				queryParams = [follow:'networkattachments']
			}
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/hosts',
                reqOptions,
                'GET'
            )

            if (!response.success)
                throw new RuntimeException("Failed to get OLVM hosts: ${extractErrorMessage(response.data)}")

            rtn.data = [hosts:response.data['host'] ?: [], connection:connection]
            rtn.success = true
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }
    static listNetworks(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def queryParams = [follow:'vnicprofiles']
            def path = '/ovirt-engine/api/networks'
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                path,
                reqOptions,
                'GET'
            )
            if (response.success) {
                def networks = []
                for (network in response.data.network) {
                    def profiles = network['vnic_profiles']?.getAt('vnic_profile')
                    if (profiles) {
                        for (profile in profiles) {
                            networks << [
                                id:profile.id,
								profileNetworkId: network.id,
                                name:profile.name,
                                provisionable:true,
                                vlanId:network.vlan?.id,
                                datacenterId:network['data_center']?.id,
                                description:profile.description ?: network.description,
                                type:'profile'
                            ]
                        }
                    }
                    else {
                        networks << [
                            id:network.id,
                            name:network.name,
                            provisionable:false,
                            vlanId:network.vlan?.id,
                            datacenterId:network['data_center']?.id,
                            description:network.description,
                            type:'network'
                        ]

                    }
                }
                rtn.data = [
                    connection: connection,
                    networks:networks
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get networks: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get networks: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get networks: ${t.message}", t)
            rtn.error = "Unable to get networks: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static listStorageDomains(opts) {
        ServiceResponse rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            def connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def path = '/ovirt-engine/api/storagedomains'
            if(opts.datacenterId) {
                path = "/ovirt-engine/api/datacenters/${opts.datacenterId}/storagedomains".toString()
            }
            def response = client.callJsonApi(
                connection.apiUrl,
                path,
                reqOptions,
                'GET'
            )
            if (response.success) {
                rtn.data = [
                    connection:connection,
                    storageDomains:response.data['storage_domain']
                ]
                rtn.success = true
            }
            else {
                log.error("Unable to get storage domains: ${extractErrorMessage(response.data)}")
                rtn.error = "Unable to get storage domains: ${extractErrorMessage(response.data)}"
            }
        }
        catch (Throwable t) {
            log.error("Unable to get storage domains: ${t.message}", t)
            rtn.error = "Unable to get storage domains: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static validateServerConfig(MorpheusContext morpheusContext, Map opts =[:]) {
        def rtn = [success:false, errors: []]
        try {
            def cloudId = opts.cloud?.id?.toLong() ?: opts.zoneId?.toLong()
            def cloud = morpheusContext.async.cloud.getCloudById(cloudId).blockingGet()
            def configMap = cloud.configMap
            // Validate enough Elastic IPs left
            if(configMap.datacenter == 'all' && !opts.config?.datacenterId) {
                rtn.errors += [field: 'datacenterId', msg: 'You must choose a datacenter']
            }
            if(!opts.config.clusterId) {
                rtn.errors += [field: 'clusterId', msg: 'You must choose a cluster']
            }
            rtn.success = (rtn.errors.size() == 0)
            log.debug("validateServerConfig results: ${rtn}")
        }
        catch(e)  {
            log.error "error in validateServerConfig: ${e}", e
        }
        return rtn
    }

    static loadImage(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client

        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)

            if (opts.imageName) {
                // if using just an image name, we must leverage the search service
                def imageName = opts.imageName.replaceAll(' ', '_')
                def queryParams = [search:"name=${imageName}".toString()]
                def reqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
                def response = client.callJsonApi(
                    connection.apiUrl,
                    '/ovirt-engine/api/templates',
                    reqOptions,
                    'GET'
                )

                if (response.success) {
                    def templates = response.data.template
                    rtn.data = [template: (templates?.size() > 0 ? templates.first() : null)]
                    rtn.success = true
                }
                else {
                    rtn.error = "Failed to retrieve image ${opts.imageName}:${extractErrorMessage(response.data)}"
                    rtn.success = false
                }
            }
            else if (opts.imageId) {
                def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
                def response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/templates/${opts.imageId}".toString(),
                    reqOptions,
                    'GET'
                )
                if (response.success) {
                    rtn.data = [template: response.data]
                    rtn.success = true
                }
                else {
                    rtn.error = "Failed to retrieve image ${opts.imageId}:${extractErrorMessage(response.data)}"
                    rtn.success = false
                }
            }
        }
        catch (Throwable t) {
            log.error("Unable to load image from OLVM cloud: ${t.message}")
            rtn.error = "Unable to load image from OLVM cloud: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static insertImage(opts) {
        log.debug("createServer opts: ${opts}")
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            // first lets see if our image exists in olvm cloud
            def vi = opts.image.virtualImage
            def imageName = vi.name.replaceAll(' ', '_')
            def headers = getAuthenticatedBaseHeaders(connection)
            def queryParams = [search:"name=${imageName}".toString()]
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/templates',
                reqOptions,
                'GET'
            )
            if (response.data.template?.size() > 0) {
                rtn.success = true
                rtn.data = [imageRef:response.data.template.first().id]
            }
            else {
                // grab the inputstream of our qcow file and wrap in QcowInputStream to get the size of the disk we need
                CloudFile cloudFile = opts.image.imageFile
                Qcow2InputStream qcowInputStream = new Qcow2InputStream(cloudFile.inputStream, true)
                def actualDiskSize = qcowInputStream.qcowHeader.size

                // create a blank disk
                def tmpDiskName = "morph_tmp_${opts.image.virtualImage.id}"
                def postHeaders = getAuthenticatedBaseHeaders(connection)
                def reqBody = [
                    name:tmpDiskName,
                    format:'cow',
                    'provisioned_size':actualDiskSize,
                    'storage_domains':[
                        'storage_domain':[[id:opts.storageDomainId]]
                    ]
                ]
                def postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
                response = client.callJsonApi(
                    connection.apiUrl,
                    '/ovirt-engine/api/disks',
                    postReqOptions,
                    'POST'
                )
                def blank = response.data

                // Wait for disk to be ready before we start data transfer
                reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
                waitForSomeStuffToHappen([label: "Waiting for blank disk to be ready"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        blank.href,
                        reqOptions,
                        'GET'
                    )
                    return response.data.status == 'ok'
                }

                // create an image transfer session
                def blankDiskId = blank.id
                reqBody = [disk:[id:blankDiskId], direction:'upload', 'inactivity_timeout':15 * 60]
                postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
                def transferSession = client.callJsonApi(
                    connection.apiUrl,
                    '/ovirt-engine/api/imagetransfers',
                    postReqOptions,
                    'POST'
                )

                if (!transferSession.success) {
                    throw new RuntimeException("Failed to initialize transfer session:${extractErrorMessage(transferSession.data)}")
                }

                def transferObj = transferSession.data
                def xferId = transferObj.id

                // wait for the image transfer session to hit the transferring state
                def imageXfer
                waitForSomeStuffToHappen([label: "Waiting for image transfer to go TRANSFERRING"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        "/ovirt-engine/api/imagetransfers/${xferId}".toString(),
                        reqOptions,
                        'GET'
                    )
                    imageXfer = response.data
                    log.debug("Image transfer phase is ${imageXfer.phase}")
                    return imageXfer.phase == 'transferring'
                }

                // Now push the file contents of our qcow file to OLVM
                def transferUrl = new URL(imageXfer['proxy_url'])

                // TODO: should we use a progress input stream here to update as we upload?
                pushDataToTarget(qcowInputStream, transferUrl, connection, cloudFile.contentLength)

                // once data has been pushed to target, finalize the transfer
                postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:[:], ignoreSSL:(connection?.ignoreSSL != false))
                response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/imagetransfers/${xferId}/finalize".toString(),
                    postReqOptions,
                    'POST'
                )

                if (!response.success) {
                    throw new RuntimeException("Failed to finalize image transfer session: ${extractErrorMessage(response.data)}")
                }

                // after we finalize the transfer wait for the image disk to be ok
                waitForSomeStuffToHappen([label: "Wait for disk ${blank.name}"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        blank.href,
                        reqOptions,
                        'GET'
                    )
                    def d = response.data
                    log.debug("Disk ${d.name}(${d.id}) status is ${d.status}")
                    actualDiskSize = d['actual_size']
                    return d.status == 'ok'
                }

                // HACK: OLVM is so stupid.  There is a lag between when the api reports the disk status as OK and when
                // the disk can be used for a VM.  If done too quickly afterwards, the VM created will fail saying the
                // disk is locked, when its actually not.  So we are forcing a 3 second delay between the two operations
                sleep(3000l)

                // TODO: Next we need to create a temporary VM and attach our disk to it
                reqBody = [
                    name:"morpheus-tmp-${opts.image.virtualImage.id}".toString(),
                    cluster:[id:opts.clusterRef],
                    template:[id:'00000000-0000-0000-0000-000000000000']
                ]
                postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
                response = client.callJsonApi(
                    connection.apiUrl,
                    '/ovirt-engine/api/vms',
                    postReqOptions,
                    'POST'
                )

                if (!response.success) {
                    throw new RuntimeException("Failed to create vm for template: ${extractErrorMessage(response.data)}")
                }

                def newVm = response.data

                // wait for new VM to be ready
                waitForSomeStuffToHappen([label: "Waiting for vm ${newVm.name} to be ready"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        "/ovirt-engine/api/vms/${newVm.id}".toString(),
                        reqOptions,
                        'GET'
                    )
                    def v = response.data
                    log.debug("Vm ${v.name}(${v.id}) status is ${v.status}")
                    return v.status == 'down'
                }

                // attach our disk to our temporary vm
                reqBody = [
                    bootable:true,
                    interface:'virtio',
                    active:true,
                    disk:[id:blankDiskId]
                ]
                postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
                response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${newVm.id}/diskattachments",
                    postReqOptions,
                    'POST'
                )

                if (!response.success) {
                    throw new RuntimeException("Failed to attach disk to vm: ${extractErrorMessage(response.data)}")
                }

                // wait for disk attachment to complete
                waitForSomeStuffToHappen([label: "Waiting disk ${blankDiskId} to be ready"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        blank.href,
                        reqOptions,
                        'GET'
                    )
                    def d = response.data
                    log.debug("Disk ${d.name}(${d.id}) status is ${d.status}")
                    return d.status == 'ok'
                }

                // create template from this vm
                reqBody = [name:imageName, vm:[id:newVm.id]]
                postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
                response = client.callJsonApi(
                    connection.apiUrl,
                    '/ovirt-engine/api/templates',
                    postReqOptions,
                    'POST'
                )

                if (!response.success) {
                    throw new RuntimeException("Failed to create template from vm: ${extractErrorMessage(response.data)}")
                }
                def newTemplate = response.data

                // wait for template to be usable
                waitForSomeStuffToHappen([label: "Waiting for template ${imageName} to be ready"]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        newTemplate.href,
                        reqOptions,
                        'GET'
                    )
                    def t = response.data
                    log.debug("Template ${t.name}(${t.id}) status is ${t.status}")
                    return t.status == 'ok'
                }

                // once our template is ready for use, remove our temporary vm
                deleteServer([connection:connection, vmId:newVm.id])

                rtn.data = [imageRef:newTemplate.id]
                rtn.success = true
            }
        }
        catch(Throwable t) {
            log.error("Failed to create image template: ${t.message}", t)
            rtn.error = "Failed to create image template: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static pushDataToTarget(Qcow2InputStream inputStream, URL url, Map con, Long contentLength = null) {
        HttpURLConnection connection
        try {
            // Install the all-trusting trust manager
            TrustManager[] trustAllCertificates = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() {
                        return null
                    }

                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                    }
                }
            }

            // Create a SSL context with the all-trusting manager
            SSLContext sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCertificates, new java.security.SecureRandom())

            // Open a connection to the URL
            connection = (HttpURLConnection) url.openConnection()

            // Scope the all-trusting SSL context to THIS connection only — never mutate
            // the JVM-wide default, since Morpheus runs plugins in a shared process and
            // a global override would silently disable TLS verification for every other
            // HTTPS connection in that process.
            if (connection instanceof HttpsURLConnection) {
                ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory())
            }
            else {
                // Fail fast rather than sending the Authorization token and image bytes
                // over plaintext HTTP.
                throw new IllegalStateException("pushDataToTarget target URL is not HTTPS (${url}); refusing to upload credentials/data over plain HTTP")
            }

            // Set connection properties
            connection.setRequestMethod("PUT")
            connection.setDoOutput(true); // Indicates that this connection will send data
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Authorization", "Bearer ${con.token}".toString())

            if (contentLength) {
                connection.setFixedLengthStreamingMode(contentLength)
            }
            else {
                connection.setChunkedStreamingMode(2 * 1024 * 1024) // default to 2 MB chunks
            }

            // Get the OutputStream from the connection
            OutputStream outputStream = connection.getOutputStream()

            // Define a buffer for reading from the InputStream
            byte[] buffer = new byte[2048]
            int bytesRead

            // first write all the bytes cached by the Qcow2InputStream that were read to generate the qcow header
            outputStream.write(inputStream.qcowHeader.bytes)

            // Read from the InputStream and write to the OutputStream
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            inputStream.close()
            outputStream.close()

            // Get the response code
            int responseCode = connection.getResponseCode()
            log.info("Response Code: ${responseCode}")
        }
        finally {
            connection?.disconnect()
        }
    }

    static createServer(opts) {
        log.debug("createServer opts: ${opts}")
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection getToken(opts.cloud)
            }
            def imageRef = opts.imageRef
            def zoneRef = opts.zoneRef
            def networkRef = opts.subnetRef
            def count = opts.count ?: 1

            // grab http client to use for provisioning requests
            client = getApiClient(connection)

            // grab template information
            def headers = getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/templates/${imageRef}/diskattachments".toString(),
                reqOptions,
                'GET'
            )

            // Get ALL template disk attachments — not just the first — so every disk gets a storage domain mapping
            def templateDiskAttachments = response.data['disk_attachment']
            if (!(templateDiskAttachments instanceof List)) {
                templateDiskAttachments = [templateDiskAttachments]
            }

            def bootableTemplateDisk = templateDiskAttachments.find { it.bootable?.toBoolean() } ?: templateDiskAttachments.first()
            def nonBootableTemplateDisks = templateDiskAttachments.findAll { it.id != bootableTemplateDisk.id }

            // Root (bootable) disk maps to rootVolume's datastore (user-selected)
            def rootStorageDomainId = opts.rootVolume.datastore.externalId
            log.debug("createServer: root disk id=${bootableTemplateDisk.id} -> storage domain ${rootStorageDomainId}")
            def sparse = opts.diskProvisioning != 'preallocated'
            def diskAttachmentList = []
            diskAttachmentList << [disk:[
                id: bootableTemplateDisk.id,
                'provisioned_size': opts.rootVolume.maxStorage,
                format: 'cow',
                sparse: sparse,
                'storage_domains': ['storage_domain': [[id: rootStorageDomainId]]]
            ]]

            // Non-bootable template disks: prefer user-specified datastore from Morpheus StorageVolume,
            // but fall back to the template disk's own storage domain fetched directly from oVirt.
            // This avoids "Storage Domain doesn't exist" errors from stale/missing Morpheus datastore records.
            nonBootableTemplateDisks.eachWithIndex { nonBootableDisk, i ->
                // Bounds-safe access: opts.dataDisks may have fewer entries than template non-boot disks
                def userDatastoreId = (opts.dataDisks?.size() > i) ? opts.dataDisks[i]?.datastore?.externalId : null

                def targetDatastoreId
                if (userDatastoreId) {
                    targetDatastoreId = userDatastoreId
                    log.debug("createServer: data disk[${i}] id=${nonBootableDisk.id} -> user-specified storage domain ${targetDatastoreId}")
                } else {
                    // Fetch this disk's storage domain directly from oVirt — authoritative source
                    def diskHref = nonBootableDisk.disk?.href ?: "/ovirt-engine/api/disks/${nonBootableDisk.id}".toString()
                    def diskDetail = client.callJsonApi(connection.apiUrl, diskHref, reqOptions, 'GET')
                    def templateDiskStorageDomainId = diskDetail.data?.storage_domains?.storage_domain?.first()?.id
                    targetDatastoreId = templateDiskStorageDomainId ?: rootStorageDomainId
                    log.debug("createServer: data disk[${i}] id=${nonBootableDisk.id} -> template disk's storage domain ${targetDatastoreId} (fetched from oVirt)")
                }

                diskAttachmentList << [disk:[
                    id: nonBootableDisk.id,
                    format: 'cow',
                    sparse: sparse,
                    'storage_domains': ['storage_domain': [[id: targetDatastoreId]]]
                ]]
            }

            // merge network interfaces — guard against null primaryInterface and null extraInterfaces list
            def interfaces = []
            if (opts.networkConfig?.primaryInterface) {
                interfaces << opts.networkConfig.primaryInterface
            }
            if (opts.networkConfig?.extraInterfaces) {
                interfaces.addAll(opts.networkConfig.extraInterfaces)
            }

            // send vm create to api
            def biosType = opts.config?.biosType ?: opts.biosType ?: opts.server?.configMap?.biosType
            def postBody = [
                name:opts.name,
                cluster:[id:opts.clusterRef],
                template:[id:imageRef],
                memory:opts.maxMemory,
                cpu:buildCpus(opts.workloadConfig),
                nics:[nic:buildNetworkInterfaces(interfaces)],
                'disk_attachments':['disk_attachment': diskAttachmentList],
                stateless: opts.stateless ?: false,
            ]
            if (biosType) {
                postBody.bios = [type: biosType]
                log.debug("createServer: setting vm bios type to ${biosType}")
            }
            if (opts.hostRef) {
                postBody.placement_policy = [
                    hosts: [host: [[id: opts.hostRef]]],
                    affinity: opts.hostAffinity ?: 'migratable'
                ]
                log.info("createServer: host placement hostRef=${opts.hostRef}, affinity=${postBody.placement_policy.affinity}")
            }
            log.info("createServer: stateless=${opts.stateless}, diskProvisioning=${opts.diskProvisioning}, cloneType=${opts.cloneType}")
            def postHeaders = getAuthenticatedBaseHeaders(connection)
            def postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            if (opts.cloneType == 'full') {
                postReqOptions.queryParams = [clone: 'true']
            }
            response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/vms/',
                postReqOptions,
                'POST'
            )

            if (!response.success) {
                throw new RuntimeException("Failed to create vm ${opts.name}: ${extractErrorMessage(response.data)}")
            }
            def vm = response.data
            rtn.data = [vmId:vm.id, vm:vm]
            rtn.success = true
        }
        catch (Throwable t) {
            rtn.error = "Failed to provision vm ${opts.name}: ${t.message}"
            log.error("Failed to provision vm ${opts.name}: ${t.message}", t)
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static createServerFromSnapshot(opts) {
        log.debug("createServer opts: ${opts}")
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection getToken(opts.cloud)
            }
            def snapInfo = opts.restoreSnapshot
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)
            def postBody = [
                name:opts.name,
                cluster:[id:opts.clusterRef],
                snapshots:[snapshot:[[id:snapInfo.snapshotId instanceof List ? snapInfo.snapshotId.first() : snapInfo.snapshotId]]],
                cpu:buildCpus(opts.workloadConfig),
                memory:opts.maxMemory,
                description:"From snapshot ${snapInfo.snapshotId}",
            ]
            // create the list of disks we need for our creation
            def snapDisks = []
            for (disk in snapInfo.disks) {
                snapDisks << [
                    id:disk.volumeId
                ]
            }
            postBody['disk_attachments'] = [
                'disk_attachment':snapDisks
            ]

            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/vms',
                postReqOptions,
                'POST'
            )

            if (!response.success)
                throw new RuntimeException("Failed to create vm from snapshot ${snapInfo.id}: ${extractErrorMessage(response.data)}")

            def vmId = response.data.id
            rtn.data = [vmId:vmId]
            rtn.success = true
        }
        catch (Throwable t) {
            rtn.error = "Failed to provision vm ${opts.name}: ${t.message}"
            log.error("Failed to provision vm ${opts.name}: ${t.message}")
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static startVmWithCloudInit(opts) {
        def rtn
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def vmExternalId = opts.server?.externalId ?: opts.vmId
            def headers = getAuthenticatedBaseHeaders(connection)
            headers['Content-Type'] = 'application/json'
            client = getApiClient(connection)
            
            // Build simple initialization with ONLY cloud-init script
            def initialization = [:]
            
            if (opts.cloudInitScript) {
                initialization['custom_script'] = opts.cloudInitScript
                log.debug("startVmWithCloudInit - Using cloud-init script only")
            }
            
            def reqBody = [
                'vm': [
                    'initialization': initialization
                ],
                'async': true,
                'use_cloud_init': true
            ]
            
            log.debug("startVmWithCloudInit - Request: custom_script with ${opts.cloudInitScript?.length() ?: 0} characters")
            
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:reqBody, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmExternalId}/start".toString(),
                postReqOptions,
                'POST'
            )

            if (response.success) {
                def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
                rtn = waitForSomeStuffToHappen([label: "Start vm ${opts.server?.name}", timeout: (5l * 60l)]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        "/ovirt-engine/api/vms/${vmExternalId}".toString(),
                        reqOptions,
                        'GET'
                    )
                    return response.data.status == 'up'
                }
            }
            else {
                def errMsg = extractErrorMessage(response.data) ?: response.msg ?: 'Unknown oVirt error'
                rtn = ServiceResponse.error("Failed to start vm: ${errMsg}")
            }
        }
        catch (Throwable t) {
            log.error("startVmWithCloudInit error: ${t.message}", t)
            rtn = ServiceResponse.error("Failed to start vm: ${t.message}")
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static startVm(opts) {
        ServiceResponse rtn
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud, opts.morpheusCtx)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def vmExternalId = opts.server?.externalId ?: opts.vmId
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmExternalId}".toString(),
                reqOptions,
                'GET'
            )

            if (response.success) {
                def vm = response.data
                if (vm.status != 'up') {
                    def actionBody = [async:true]
                    def postHeaders = getAuthenticatedBaseHeaders(connection)
                    postHeaders['Content-Type'] = 'application/json'
                    def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:actionBody, ignoreSSL:(connection?.ignoreSSL != false))

                    // the start command can fail with 409 while a related operation (e.g. a
                    // just-completed restore/disk-attach) is still finishing server-side, so
                    // retry the start command itself, not just poll status, until it is accepted
                    // or the VM comes up on its own.
                    rtn = waitForSomeStuffToHappen([label: "Start vm ${opts.server?.name}", timeout: (5l * 60l)]) {
                        response = client.callJsonApi(
                            connection.apiUrl,
                            "/ovirt-engine/api/vms/${vmExternalId}".toString(),
                            reqOptions,
                            'GET'
                        )
                        if (response.data?.status == 'up') {
                            return true
                        }

                        def startResponse = client.callJsonApi(
                            connection.apiUrl,
                            "/ovirt-engine/api/vms/${vmExternalId}/start".toString(),
                            postReqOptions,
                            'POST'
                        )
                        if (!startResponse.success && startResponse.errorCode != '409') {
                            log.warn("startVm: unexpected error starting vm ${vmExternalId}: ${extractErrorMessage(startResponse.data) ?: startResponse.msg}")
                        }
                        return false
                    }
                } else {
                    // if its already started then just ignore
                    rtn = ServiceResponse.success()
                }
            }
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static stopVm(opts) {
        ServiceResponse rtn
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud, opts.morpheusCtx)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            headers['Content-Type'] = 'application/json'
            client = getApiClient(connection)
            def postBody = [async:true]
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            def vmExternalId = opts.server?.externalId ?: opts.vmId
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmExternalId}/stop".toString(),
                postReqOptions,
                'POST'
            )

            if (response.success) {
                def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
                // wait for the VM to be down
                rtn = waitForSomeStuffToHappen([label: "Stop vm ${opts.server?.name}", timeout: (5l * 60l)]) {
                    response = client.callJsonApi(
                        connection.apiUrl,
                        "/ovirt-engine/api/vms/${vmExternalId}".toString(),
                        reqOptions,
                        'GET'
                    )
                    return response.data.status == 'down'
                }
            }
            else {
                rtn = ServiceResponse.error("Failed to stop vm: ${extractErrorMessage(response.data)}")
            }
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static addNetworkInterface(opts) {
        return addNicsToVm([
            connection:opts.connection,
            vmId:opts.server?.externalId ?: opts.vmId,
            nics:[
                [name:opts.deviceName, network:[externalId:opts.networkId]]
            ]
        ])
    }

    static deleteNetworkInterface(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${opts.server?.externalId ?: opts.vmId}/nics/${opts.nicId}".toString(),
                reqOptions,
                'DELETE'
            )

            if (!response.success) {
                throw new RuntimeException("Unable to remove vm network interface ${opts.nicId}: ${extractErrorMessage(response.data)}")
            }
        }
        catch (Throwable t) {
            log.error("Unable to delete network interface ${opts.nicId}: ${t.message}", t)
            rtn.error = "Unable to delete network interface ${opts.nicId}: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static checkServerReady(opts) {
        def rtn = ServiceResponse.prepare()
        try {
            def pending = true
            def attempts = 0
            def statusUpAttempts = 0  // extra polls after status=up to give guest agent time to report IPs
            def maxStatusUpAttempts = 12  // 12 x 10s = 2 minutes; cloud-init DHCP setup can take this long
            while(pending) {
                sleep(1000l * 10l)
                def resp = getServerDetail(opts)
                def serverDetail = resp.data
                def status = serverDetail?.status
                def ipV4 = serverDetail?.ipV4
                log.debug("checkServerReady: attempt ${attempts + 1}/15 - status=${status}, ipV4=${ipV4}")
                if(resp.success == true && status == 'up') {
                    if (ipV4?.size() > 0) {
                        log.debug("checkServerReady: VM is up with IP=${ipV4.first()}")
                        rtn.success = true
                        rtn.data = serverDetail
                        pending = false
                    } else if (opts.noAgent == true) {
                        // Guest agent is not installed by configuration — no point waiting for IPs
                        log.debug("checkServerReady: VM is up, skipping IP wait (noAgent=true)")
                        rtn.success = true
                        rtn.data = serverDetail
                        pending = false
                    } else {
                        statusUpAttempts++
                        if (statusUpAttempts >= maxStatusUpAttempts) {
                            // VM is up but guest agent has not reported IPs after maxStatusUpAttempts polls -
                            // template likely has no guest agent installed; proceed without IP
                            log.debug("checkServerReady: VM is up but no IPv4 after ${statusUpAttempts} extra polls - proceeding without IP (no guest agent?)")
                            rtn.success = true
                            rtn.data = serverDetail
                            pending = false
                        } else {
                            log.debug("checkServerReady: VM is up but no IPv4 yet, waiting for guest agent (${statusUpAttempts}/${maxStatusUpAttempts} extra polls)")
                        }
                    }
                } else if (!resp.success) {
                    log.debug("checkServerReady: getServerDetail failed on attempt ${attempts + 1}: ${extractErrorMessage(resp.data) ?: resp.msg}")
                }
                attempts++
                if(pending && attempts >= 15) {
                    log.warn("checkServerReady: timed out after ${attempts} attempts - last status=${status}, ipV4=${ipV4}")
                    pending = false
                }
            }
        } catch(e) {
            log.error("An Exception Has Occurred: ${e.message}",e)
        }
        return rtn
    }

    static getServerDetail(opts) {
        def rtn = ServiceResponse.prepare()
        HttpApiClient client = null
        try {
            Map connection = opts.connection
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def externalId = opts.server?.externalId ?: opts.serverId
            if(externalId) {
                def headers = getAuthenticatedBaseHeaders(connection)
                client = getApiClient(connection)
                def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
                def response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${externalId}".toString(),
                    reqOptions,
                    'GET'
                )

                if (response.success) {
                    rtn.data = [
                        connection: connection,
                    ]
                    rtn.success = true
                }
                def vm = response.data
                def vmMap = [
                    name:vm.name,
                    hostname:vm.fqdn,
                    id:vm.id,
                    clusterId:vm.cluster?.id,
                    memory:vm.memory?.toLong(),
                    cores:vm.cpu.topology.cores.toLong() * vm.cpu.topology.sockets.toLong(),
                    sockets:vm.cpu.topology.sockets.toLong(),
                    ipV4:[],
                    ipV6:[],
                    status:vm.status,
                    disks:[],
                    nics:[]
                ]

                // add ip address to vm details
                response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${externalId}/reporteddevices".toString(),
                    reqOptions,
                    'GET'
                )
                def devices = response.data['reported_device']
                // Normalize: null → [], single Map → [map], list → list
                def deviceList = devices == null ? [] : (devices instanceof List ? devices : [devices])
                log.debug("getServerDetail: VM ${externalId} has ${deviceList.size()} reported device(s)")
                for (device in deviceList) {
                    for(ip in device.ips?.ip) {
                        if (ip.version == 'v4') {
                            vmMap.ipV4 << ip.address
                        }
                        else {
                            vmMap.ipV6 << ip.address
                        }
                    }
                }

                // add disks to vm details
                response = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${externalId}/diskattachments".toString(),
                    reqOptions,
                    'GET'
                )
                def diskAttachments = response.data['disk_attachment']
                def diskAttachmentList = diskAttachments == null ? [] : (diskAttachments instanceof List ? diskAttachments : [diskAttachments])
                for (diskAttachment in diskAttachmentList) {
                    def disk = client.callJsonApi(
                        connection.apiUrl,
                        diskAttachment.disk.href,
                        reqOptions,
                        'GET'
                    ).data

                    vmMap.disks << [
                        name:disk.name,
                        id:disk.id,
                        status:disk.status,
                        size:disk['provisioned_size']?.toLong(),
                        bootable:diskAttachment.bootable.toBoolean(),
                        logicalName:diskAttachment['logical_name'],
                        interface:diskAttachment.interface,
                        active:diskAttachment.active.toBoolean()
                    ]
                }

                // add nics to vm details
                def vmNics = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${externalId}/nics".toString(),
                    reqOptions,
                    'GET'
                ).data.nic
                for (nic in vmNics) {
                    vmMap.nics << [
                        name:nic.name,
                        id:nic.id,
                        networkId:nic['vnic_profile'].id
                    ]
                }
                rtn.data = vmMap
                rtn.success = true
            }
        }
        catch(Throwable t) {
            log.error("getServerDetail error: ${t}", t)
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static updateDiskSize(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)
            def cloudDisk = client.callJsonApi(connection.apiUrl, "/ovirt-engine/api/disks/${opts.disk.id}", reqOptions, 'GET').data

            // cannot down size a disk, so only run if its sizing up
            if (cloudDisk['provisioned_size']?.toLong() < opts.disk.size) {
                def putHeaders = getAuthenticatedBaseHeaders(connection)
                def putBody = [id:opts.disk.id, 'provisioned_size':opts.disk.size]
                def putReqOptions = new HttpApiClient.RequestOptions(headers:putHeaders, body:putBody, ignoreSSL:(connection?.ignoreSSL != false))
                def updateResponse = client.callJsonApi(
                    connection.apiUrl,
                    cloudDisk.href,
                    putReqOptions,
                    'PUT'
                )

                if (!updateResponse.success) {
                    throw new RuntimeException("Unable to resize disk ${cloudDisk.name}: ${updateResponse.data?.success}")
                }

                // wait for the disk update to complete
                waitForSomeStuffToHappen([label: "Resize disk ${opts.disk.id}"]) {
                    // need to wait for disk status before we move on
                    log.debug("checking status on disk ${opts.disk.id}")
                    def d = client.callJsonApi(connection.apiUrl, cloudDisk.href, reqOptions, 'GET').data
                    return d.status == 'ok'
                }
            }
            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Unable to resize disk: ${t.message}", t)
            rtn.error = "Unable to resize disk: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static addDisksToVm(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        def vmId = opts.vm?.id ?: opts.vmId
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def attachedDisks = []
            for (vol in opts.disks) {
                def diskAddResults = addDiskToVm([
                    connection:connection, vmId:vmId, disk:vol
                ])

                // once ok.... going to set externalId on disk before returning
                if (diskAddResults.success) {
                    attachedDisks << [externalId:diskAddResults.data.externalId, name:vol.name, size:vol.maxStorage, device:vol.deviceName]
                }
            }
            rtn.success = true
            rtn.data = [disks:attachedDisks]
        }
        catch (Throwable t) {
            log.error("Failed to add disks to vm ${vmId}:${t.message}", t)
            rtn.error = "Failed to add disks to vm ${vmId}:${t.message}"
        }
        return rtn
    }

    static addDiskToVm(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        def vmId = opts.vm?.id ?: opts.vmId
        try {
            if (!connection) {
                connection = getConnection(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)

            // first create our disk
            def postBody = [
                name:opts.disk.name,
                description:"Device ${opts.disk.deviceName}".toString(),
                format:'cow',
                'provisioned_size':BigInteger.valueOf(opts.disk.maxStorage),
                'storage_domains':[
                    'storage_domain':[[id:opts.disk.datastore.externalId]]
                ]
            ]
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                '/ovirt-engine/api/disks',
                postReqOptions,
                'POST'
            )

            if (!response.success) {
                throw new RuntimeException("Failed to create disk ${opts.disk.name}: ${extractErrorMessage(response.data)}")
            }

            // wait for our disk to be created before we attach it to our vm
            def newDisk = response.data
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            waitForSomeStuffToHappen([label:"Waiting for disk(${newDisk.id}) to be ready"]) {
                def d = client.callJsonApi(connection.apiUrl, newDisk.href, reqOptions, 'GET').data
                return d.status == 'ok'
            }

            // now attach the disk to the vm
            postBody = [
                bootable:false,
                interface:'virtio',
                active:true,
                'logical_name':opts.disk.deviceName ?: '',
                disk:[id:newDisk.id]
            ]
            postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}/diskattachments".toString(),
                postReqOptions,
                'POST'
            )

            if (!response.success) {
                throw new RuntimeException("Failed to attach disk to vm: ${extractErrorMessage(response.data)}")
            }

            // wait for disk to be OK
            waitForSomeStuffToHappen([label:"Waiting for disk(${newDisk.id}) to be ready"]) {
                def d = client.callJsonApi(connection.apiUrl, newDisk.href, reqOptions, 'GET').data
                return d.status == 'ok'
            }

            // once disk has been added, set new disk id to return object
            opts.disk.externalId = newDisk.id
            rtn.success = true
            rtn.data = opts.disk
        }
        catch (Throwable t) {
            log.error("Unable to add disk to vm: ${t.message}", t)
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static detachVolume(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        def vmId = opts.vm?.id ?: opts.vmId
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            def queryParams = [follow:'diskattachments']
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)

            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}".toString(),
                reqOptions,
                'GET'
            )

            // find our disk attachment
            def attachment = response.data['disk_attachments']['disk_attachment'].find { attachment ->
                return attachment.disk.id == opts.volumeId
            }

            // detach the disk from our vm
            queryParams = ['detach_only':'true']
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, queryParams:queryParams, ignoreSSL:(connection?.ignoreSSL != false))
            response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}/diskattachments/${attachment.id}".toString(),
                postReqOptions,
                'DELETE'
            )

            if (!response.success)
                throw new RuntimeException("Failed to detailed disk ${attachment.id}: ${extractErrorMessage(response.data)}")

            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Unable to detach volume ${opts.volumeId}: ${t.message}")
            rtn.error = "Unable to detach volume ${opts.volumeId}: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static deleteVolume(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/disks/${opts.volumeId}".toString(),
                reqOptions,
                'DELETE'
            )

            if (!response.success)
                throw new RuntimeException("Failed to remove disk ${opts.volumeId}: ${extractErrorMessage(response.data)}")

            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Unable to remove volume ${opts.volumeId}")
            rtn.error = "Unable to remove volume ${opts.volumeId}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static updateVmProperties(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        def vmId = opts.vm?.id ?: opts.vmId
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def postHeaders = getAuthenticatedBaseHeaders(connection)
            def postBody = [:]
            client = getApiClient(connection)

            if (opts.vm.name)
                postBody.name = opts.vm.name

            if (opts.vm.memory)
                postBody.memory = opts.vm.memory.toLong()

            if (opts.vm.maxCores)
                postBody.cpu = buildCpus(opts.vm)

            def postReqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}".toString(),
                postReqOptions,
                'PUT'
            )

            if (!response.success) {
                throw new RuntimeException("Failed to update vm ${vmId}: ${extractErrorMessage(response.data)}")
            }

            def reqOptions = new HttpApiClient.RequestOptions(headers:postHeaders, ignoreSSL:(connection?.ignoreSSL != false))
            def vm
            waitForSomeStuffToHappen([label:"waiting for vm (${opts.vm.id}) to save"]) {
                vm = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${vmId}".toString(),
                    reqOptions,
                    'GET'
                ).data
                return vm.status != 'saving_state'
            }

            rtn.success = true
            rtn.data = vm
        }
        catch (Throwable t) {
            log.error("Unable to update vm ${opts.vm.name}: ${t.message}", t)
            rtn.error = "Unable to update vm ${opts.vm.name}: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
    }

    static addNicsToVm(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        def vmId = opts.vm?.id ?: opts.vmId
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }

            // add all nics in opts.nics
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)

            for (networkInterface in opts.nics) {
                def postBody = [
                    name:networkInterface.name,
                    interface:'virtio',
                    'vnic_profile':[id:networkInterface.network.externalId]
                ]
                def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
                def addResponse = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${vmId}/nics".toString(),
                    postReqOptions,
                    'POST'
                )

                if (!addResponse.success) {
                    throw new RuntimeException("Failed to add nic ${networkInterface.name} to vm: ${extractErrorMessage(addResponse.data)}")
                }
                networkInterface.externalId = addResponse.data.id
            }

            rtn.success = true
            rtn.data = opts.nics
        }
        catch (Throwable t) {
            log.error("Unable to add network interfaces to vm: ${t.message}", t)
            rtn.error = "Unable to add network interfaces to vm: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static deleteServer(opts, MorpheusContext ctx = null) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud, ctx)
            }

            // first stop our vm
            def vmId = opts.server?.externalId ?: opts.vmId
            stopVm([connection:connection, vmId:vmId])

            // then remove vm
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}".toString(),
                reqOptions,
                'DELETE'
            )
            rtn.success = response.success
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static snapshotVm(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
            }
            def headers = getAuthenticatedBaseHeaders(connection)
            def postBody = [
                description:opts.description ?: 'A morpheus initiated snapshot'
            ]
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)

            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${opts.vmId}/snapshots".toString(),
                postReqOptions,
                'POST'
            )

            if (!response.success)
                throw new RuntimeException("Failed to create snapshot for vm ${opts.vmId}: ${extractErrorMessage(response.data)}")

            // now wait for our snapshot to complete
            def snapshot = response.data
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            waitForSomeStuffToHappen([label:"Waiting for snapshot(${snapshot.id}) to be ready"]) {
                def s = client.callJsonApi(
                    connection.apiUrl,
                    snapshot.href,
                    reqOptions,
                    'GET'
                ).data
                return s['snapshot_status'] == 'ok'
            }
            rtn.data = [id:snapshot.id, href:snapshot.href, status:'ok']
            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Error creating snapshot for vm (${opts.vmId}): ${t.message}", t)
            rtn.error = "Error creating snapshot for vm (${opts.vmId}): ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static getSnapshot(opts, MorpheusContext ctx = null) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud, ctx)
            }
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${opts.vmId}/snapshots/${opts.snapshotId}".toString(),
                reqOptions,
                'GET'
            )

            if (!response.success)
                throw new RuntimeException("Failed to get snapshot ${opts.snapshotId}: ${extractErrorMessage(response.data)}")

            rtn.data = response.data
            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Unable to get snapshot info for ${opts.snapshotId}: ${t.message}", t)
            rtn.error = "Unable to get snapshot info for ${opts.snapshotId}: ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static restoreSnapshot(opts) {
        def rtn = ServiceResponse.prepare()
        Map connection = opts.connection
        HttpApiClient client
        try {
            if (!connection) {
                connection = getToken(opts.cloud)
                opts.connection = connection
            }

            // stop the vm before restoring
            stopVm(opts)

            // perform the restore
            def headers = getAuthenticatedBaseHeaders(connection)
            client = getApiClient(connection)

            // create the list of disks we are adding to the snapshot restore
            def disks = []
            for (disk in opts.disks) {
                disks << [
                    id:disk.volumeId,
                    'image_id':disk.imageId
                ]
            }
            def postBody = [disks:[disk:disks]]
            def postReqOptions = new HttpApiClient.RequestOptions(headers:headers, body:postBody, ignoreSSL:(connection?.ignoreSSL != false))
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${opts.vmId}/snapshots/${opts.snapshotId}/restore".toString(),
                postReqOptions,
                'POST'
            )

            if (!response.success)
                throw new RuntimeException("Failed to restore snapshot ${opts.snapshotId}: ${extractErrorMessage(response.data)}")

            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            waitForSomeStuffToHappen([label:"Waiting for vm restore(${opts.vmId}) to be ready"]) {
                def v = client.callJsonApi(
                    connection.apiUrl,
                    "/ovirt-engine/api/vms/${opts.vmId}".toString(),
                    reqOptions,
                    'GET'
                ).data
                return v.status != 'image_locked'
            }
            rtn.success = true
        }
        catch (Throwable t) {
            log.error("Unable to restore snapshot(${opts.snapshotId}): ${t.message}", t)
            rtn.error = "Unable to restore snapshot(${opts.snapshotId}): ${t.message}"
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    static findQcowFile(Collection fileList) {
        def rtn
        if(fileList) {
            def matchList = []
            for(CloudFile file in fileList) {
                def filePath = file.name.toLowerCase();
                def fileName = getLastPath(filePath)
				//looking for non tmp file qcow files
                if((fileName.endsWith('.qcow') == true || fileName.endsWith('.qcow2') == true ) && fileName.startsWith('.') != true)
                    matchList << file
            }
            if(matchList.size() > 0)
                rtn = matchList[0]
        }
        return rtn
    }

    static getLastPath(String path) {
        def rtn = path
        if(path) {
            def tokens = path.tokenize('/')
            if(tokens.size() > 0)
                rtn = tokens[tokens.size() - 1]
        }
        return rtn
    }

    static waitForServerExists(opts) {
        Map connection = opts.connection
        HttpApiClient client
        if (!connection) {
            connection = getToken(opts.cloud)
        }
        try {
            def headers = getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:(connection?.ignoreSSL != false))
            client = getApiClient(connection)

            // first wait for the vm to unlock
            waitForSomeStuffToHappen([label: "Create vm ${opts.server.name}"]) {
                // we need to wait till our vm status is equal to DOWN so we know it has finished creating
                def vm = client.callJsonApi(connection.apiUrl, "/ovirt-engine/api/vms/${opts.server.externalId}".toString(), reqOptions, 'GET').data
                log.debug("VM ${opts.server.name}(${opts.server.externalId}) status is ${vm.status}")
                return vm.status == 'down'
            }

            // then wait for each disk to be unlocked
            def diskAttachments = client.callJsonApi(connection.apiUrl, "/ovirt-engine/api/vms/${opts.server.externalId}/diskattachments".toString(), reqOptions, 'GET').data
            for (attachment in diskAttachments['disk_attachment']) {
                waitForSomeStuffToHappen([label:"Disk status for ${attachment['logical_name']}"]) {
                    def myDisk = client.callJsonApi(connection.apiUrl, attachment.disk.href, reqOptions, 'GET').data
                    log.debug("Disk ${myDisk.name} status is ${myDisk.status}")
                    return myDisk.status == 'ok'
                }
            }
        }
        finally {
            client?.shutdownClient()
        }
    }

    static testConnection(Cloud cloud) {
        def rtn = ServiceResponse.prepare()
        rtn.data = [invalidLogin:false]

        Map connection = getToken(cloud)
        HttpApiClient client = null
        try {
            client = getApiClient(connection)
            def headers = getAuthenticatedBaseHeaders(connection)
            if(headers) {
                def reqOptions = new HttpApiClient.RequestOptions(headers: headers, ignoreSSL: (connection?.ignoreSSL != false))
                def resp = client.callJsonApi(
                        connection.apiUrl,
                        '/ovirt-engine/api',
                        reqOptions,
                        'GET'
                )
                if (resp.success) {
                    rtn.success = true
                    rtn.data.connection = connection
                } else {
                    log.error("Failed testConnection(): ${extractErrorMessage(resp.data)}")
                    rtn.error = extractErrorMessage(resp.data)
                }
            } else {
                log.error("Failed to get a token")
                rtn.error = "Failed to get a token"
                rtn.success = false
            }
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    /**
     * utility method used to wait on something (closure body) to happen.  The closure must a return a boolean
     * value to represent success
     * @param opts
     *        timeout: specify a timeout in SECONDS
     * @param clos
     * @return {@link ServiceResponse} with success condition
     */
    static ServiceResponse waitForSomeStuffToHappen(Map opts, Closure clos) {
        def rtn = ServiceResponse.prepare()
        def timeout = opts.timeout ?: DEFAULT_WAIT_TIMEOUT
        def startTime = System.currentTimeMillis()
        def conditionMet = false

        while (System.currentTimeMillis() - startTime < (timeout * 1000l)) {
            if (clos.call(opts)) {
                conditionMet = true
                break
            }
            sleep(3000l)
        }
        if (!conditionMet) {
            rtn.success = false
            rtn.error = "Operation timed out: ${opts.label ?: 'operation not labeled'}"
        }
        else {
            rtn.success = true
        }
        return rtn
    }

    static List<Map> buildNetworkInterfaces(List interfaces) {
        def nics = []
        for (iface in interfaces) {
            if (iface != null) {
                nics << buildNetworkInterface(iface)
            }
        }
        return nics
    }

    static Map buildNetworkInterface(opts) {
        return [
            name:opts.name,
            'vnic_profile':[id:opts.externalId ?: opts.networkId]
        ]
    }

    static Map buildCpus(Map opts) {
        return [
            topology:[
                cores:opts.maxCores.toInteger(),
                sockets:opts.coresPerSocket?.toInteger() ?: 1
            ]
        ]
    }

    static getOsTypeCode(String olvmCode) {
        def code = 'unknown'
        switch (olvmCode) {
            case 'rhel':
                code = 'redhat.64'
                break
            case 'ol':
                code = 'oracle.linux.64'
                break
            case 'windows':
                code = olvmCode
                break
            case 'centos':
                code = 'cent.64'
                break
            case 'ubuntu':
                code = 'ubuntu.64'
                break
            case 'debian':
                code = 'debian.64'
                break
            case 'fedora':
                code = 'fedora.64'
                break
            case 'sles':
                code = 'suse.64'
                break
        }
        return code
    }

    static getToken(Cloud cloud, MorpheusContext ctx = null) {
        if (!cloud.accountCredentialLoaded && ctx) {
            cloud.accountCredentialData = ctx.async.cloud.loadCredentials(cloud.id).blockingGet()?.data
            cloud.accountCredentialLoaded = true
        }

        // TLS certificate validation is skipped by default for backward compatibility, but operators can
        // opt in to verification via the "Enable SSL Verification" cloud config checkbox.
        Boolean ignoreSSL = !(cloud.configMap?.enableSslVerification == 'on')

        def config = [
            endpointUrl:cloud.serviceUrl,
            serviceUsername:cloud.accountCredentialData?.username ?: cloud.serviceUsername,
            servicePassword:cloud.accountCredentialData?.password ?: cloud.servicePassword,
            ignoreSSL:ignoreSSL
        ]

        if (cloud.apiProxy) {
            config.apiProxy = cloud.apiProxy
        }

        HttpApiClient client
        try {
            client = getApiClient(config)
            def headers = ['Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json']
            def body = "grant_type=password&scope=ovirt-app-api&username=${URLEncoder.encode(config.serviceUsername, 'UTF-8')}&password=${URLEncoder.encode(config.servicePassword, 'UTF-8')}".toString()
            HttpApiClient.RequestOptions reqOptions = new HttpApiClient.RequestOptions(headers: headers, body: body, ignoreSSL: ignoreSSL)
            def resp = client.callJsonApi(
                extractRootURL(config.endpointUrl),
                '/ovirt-engine/sso/oauth/token',
                reqOptions,
                'POST'
            )

            if (resp.success) {
                return [success:true, apiUrl: extractRootURL(config.endpointUrl), token: resp.data['access_token'], ignoreSSL:ignoreSSL]
            } else {
                log.error("Failed to authenticate to OLVM environment: ${extractErrorMessage(resp.data)}")
                return [success:false, apiUrl: extractRootURL(config.endpointUrl), error:extractErrorMessage(resp.data), ignoreSSL:ignoreSSL]
            }
        }
        catch (Throwable t) {
            return [success:false, apiUrl: extractRootURL(config.endpointUrl), error:t.message]
        }
        finally {
            client?.shutdownClient()
        }
    }

    static getConnection(Cloud cloud, MorpheusContext ctx = null) {
        if (!cloud.accountCredentialLoaded && ctx) {
            cloud.accountCredentialData = ctx.async.cloud.loadCredentials(cloud.id).blockingGet()?.data
            cloud.accountCredentialLoaded = true
        }

        def config = [
            endpointUrl:cloud.serviceUrl,
            serviceUsername:cloud.accountCredentialData?.username ?: cloud.serviceUsername,
            servicePassword:cloud.accountCredentialData?.password ?: cloud.servicePassword,
            ignoreSSL: !(cloud.configMap?.enableSslVerification == 'on')
        ]

        if (cloud.apiProxy) {
            config.apiProxy = cloud.apiProxy
        }

        return getConnection(config)
    }

    static getDeviceName(Integer index) {
        return "/dev/vd${BLOCK_DEVICE_LIST[index]}".toString()
    }

    static HttpApiClient getApiClient(Map config) {
        return new HttpApiClient(networkProxy:config.apiProxy)
    }

    static getAuthenticatedBaseHeaders(String token) {
        return [Authorization:"Bearer ${token}".toString(), Accept:'application/json', 'Content-Type':'application/json']
    }

    static getAuthenticatedBaseHeaders(Map connection) {
        if(connection) {
            return getAuthenticatedBaseHeaders(connection.token)
        } else {
            return null
        }

    }

    static String extractErrorMessage(resp) {
        if (resp?.fault) {
            def reason = resp.fault?.reason ?: ''
            def detail = resp.fault?.detail ?: ''
            if (reason && detail) return "${reason} - ${detail}"
            return reason ?: detail ?: null
        }
        def msg = resp?.message ?: ''
        def detail = resp?.detail ?: ''
        if (msg && detail) return "${msg} - ${detail}"
        return msg ?: detail ?: null
    }

    private static String extractRootURL(String urlString) {
        URL url = new URL(urlString)
        return "${url.protocol}://${url.host}".toString()
    }

    public static final List BLOCK_DEVICE_LIST = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l']
}
