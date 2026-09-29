package com.morpheus.olvm.backup

import com.morpheus.olvm.OlvmCloudPlugin
import com.morpheus.olvm.util.OlvmComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.projection.BackupResultIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.schedulers.Schedulers

@Slf4j
class OlvmSnapshotExecutionProvider implements BackupExecutionProvider {
    OlvmCloudPlugin plugin
    MorpheusContext morpheus

    public OlvmSnapshotExecutionProvider(OlvmCloudPlugin plugin, MorpheusContext ctx) {
        super()
        this.@plugin = plugin
        this.morpheus = ctx
    }

    @Override
    ServiceResponse configureBackup(Backup backup, Map config, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse validateBackup(Backup backup, Map config, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse createBackup(Backup backup, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse deleteBackup(Backup backup, Map opts) {
        def rtn = ServiceResponse.success()
        try {
            morpheus.async.backup.backupResult.listIdentityProjections(backup)
                .subscribeOn(Schedulers.io())
                .buffer(50)
                .concatMap {List<BackupResultIdentityProjection> backupResults ->
                    morpheus.async.backup.backupResult.listById(backupResults.collect { it.id})
                }
                .doOnNext { BackupResult backupResult ->
                    deleteBackupResult(backupResult, opts)
                }.blockingSubscribe()
        } catch (Exception ex) {
            log.error("deleteBackup error: {}", ex, ex)
            rtn.success = false
        }

        return rtn
    }

    @Override
    ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
        def rtn = ServiceResponse.success()
        try {
            def cloudId = backupResult.zoneId ?: backupResult.backup.zoneId
            def snapshotIds = backupResult?.configMap?.snapshots?.collect{ it.snapshotId }.unique()

            def cloud
            def backupConfig = backupResult.configMap
            if(cloudId) {
                cloud = morpheus.async.cloud.get(cloudId.toLong()).blockingGet()
            } else {
                def container = morpheus.async.workload.get(backupResult.containerId).blockingGet()
                def server = container?.server
                cloud = server?.cloud
            }

            if(cloud) {
                snapshotIds.each{ snapshotId ->
                    def result = deleteSnapshot(backupConfig.vmId, snapshotId, cloud)
                    if(!result.success) {
                        rtn.success = false
                    }
                }
            }
        }
        catch(Exception ex) {
            log.error("deleteBackupResult error: {}", ex, ex)
        }

        return rtn
    }

    @Override
    ServiceResponse prepareExecuteBackup(Backup backupModel, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
        ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

        try {
            log.debug("backupConfig container: {}", rtn)

            def container
            def vmId = computeServer.externalId

            //execute snapshot
            def result = createSnapshotsForVm(vmId, cloud, computeServer)
            if(result.success) {
                def totalSize = result.snapshot.totalSize.toLong()
                def targetArchive = []
                def snapshotDisks = []
                result.snapshot?.disks.each { disk ->
                    def volumeSize = disk.provisionedSize.toLong()
                    targetArchive << result.snapshot.snapshotId
                    snapshotDisks << [
                        volumeId:disk.id, imageId:disk.imageId, snapshotId:result.snapshot.snapshotId, volumeSize:volumeSize,
                        storageDomainId:disk.storageDomainId, sizeInMb:(volumeSize / ComputeUtility.ONE_MEGABYTE), deviceName:disk.name
                    ]
                }
                rtn.data.backupResult.zoneId
                rtn.data.backupResult.status = BackupStatusUtility.IN_PROGRESS
                rtn.data.backupResult.resultArchive = targetArchive.join(",")
                rtn.data.backupResult.sizeInMb = totalSize / ComputeUtility.ONE_MEGABYTE
                rtn.data.backupResult.setConfigProperty("snapshots", snapshotDisks)
                rtn.data.backupResult.setConfigProperty("snapshotId", result.snapshot.snapshotId)
                rtn.data.backupResult.setConfigProperty("vmId", vmId)
                rtn.data.updates = true
            } else {
                rtn.data.backupResult.sizeInMb = 0
                rtn.data.backupResult.status = BackupStatusUtility.FAILED
                rtn.data.updates = true
            }
            rtn.success = true
        } catch(e) {
            log.error("executeBackup: ${e}", e)
            rtn.msg = e.getMessage()
            rtn.data.backupResult.errorOutput = "Failed to execute backup".encodeAsBase64()
            rtn.data.backupResult.sizeInMb = 0
            rtn.data.backupResult.status = BackupStatusUtility.FAILED
            rtn.data.updates = true
        }
        return rtn
    }

    @Override
    ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
        ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))
        log.debug("syncBackupResult {}", backupResult)

        try {
            def amazonClient
            ComputeServer server = morpheus.async.computeServer.get(backupResult.serverId).blockingGet()
            Cloud cloud = server?.cloud

            def snapshotIds = backupResult?.configMap?.snapshots?.collect { it.snapshotId }?.unique()

            // if cannot find snapshotIds, just skip
            if (!snapshotIds) {
                rtn.data.updates = false
                rtn.success = true
                return rtn
            }

            def completeCount = 0
            Boolean error = false
            snapshotIds?.each{ snapshotId ->
                def opts = [vmId:server.externalId, snapshotId:snapshotId, cloud:cloud]
                def snapshotResults = OlvmComputeUtility.getSnapshot(opts, morpheus)
                if(snapshotResults.success == true) {
                    def snapshot = snapshotResults.data
                    if(snapshot['snapshot_status'] == 'ok') {
                        completeCount++
                    }
                }
                else {
                    error = true
                }
            }

            if(!error && completeCount == snapshotIds?.size()) { //all complete, update status
                rtn.data.updates = true
                rtn.data.backupResult.status = BackupStatusUtility.SUCCEEDED
                if(!backupResult.endDate) {
                    rtn.data.backupResult.endDate = new Date()
                    def startDate = backupResult.startDate
                    if(startDate) {
                        def start = DateUtility.parseDate(startDate)
                        def end = rtn.data.backupResult.endDate
                        rtn.data.backupResult.durationMillis = end.time - start.time
                    }
                }
                rtn.success = true
            }
            else if(error) {
                rtn.data.updates = true
                rtn.data.backupResult.status = BackupStatusUtility.FAILED
                if(!backupResult.endDate) {
                    rtn.data.backupResult.endDate = new Date()
                    def startDate = backupResult.startDate
                    if(startDate) {
                        def start = DateUtility.parseDate(startDate)
                        def end = rtn.data.backupResult.endDate
                        rtn.data.backupResult.durationMillis = end.time - start.time
                    }
                }
            } else {
                rtn.data.updates = false
                rtn.success = true
            }
        } catch (Exception e) {
            log.error("refreshBackupResult error: {}", e, e)
        }

        return rtn
    }

    protected createSnapshotsForVm(vmId, Cloud cloud, ComputeServer computeServer = null){
        log.debug("createSnapshotsForVm: ${vmId}")
        def rtn = [success:true]
        def eligibleForCacheReset = isCloudInitResetEligible(computeServer)
        if(eligibleForCacheReset) {
            resetCloudInitCache(computeServer)
        }
        try {
            rtn.snapshot = createSnapshot(vmId, cloud)
        } finally {
            if(eligibleForCacheReset) {
                restoreCloudInitCache(computeServer)
            }
        }
        return rtn
    }

    /**
     * Determines whether the source VM is a Linux cloud-init image, in which case its cloud-init cache markers
     * should be reset immediately before snapshotting. This mirrors the convention used by the VMware, KVM, and
     * SCVMM plugins so a VM restored from this snapshot gets a fresh cloud-init run (network config, hostname,
     * SSH host keys, etc.) instead of reusing the source VM's cached instance identity.
     */
    protected Boolean isCloudInitResetEligible(ComputeServer computeServer) {
        return computeServer?.sourceImage?.isCloudInit() && computeServer?.serverOs?.platform?.toString() != 'windows'
    }

    /**
     * Clears the source VM's cloud-init cache-suppression marker and blanks its machine-id so the upcoming
     * snapshot captures a "fresh" cloud-init state. The live source VM is left untouched otherwise; its
     * original machine-id is restored via {@link #restoreCloudInitCache} immediately after the snapshot completes.
     */
    protected void resetCloudInitCache(ComputeServer computeServer) {
        try {
            log.info("Resetting Machine-Id before snapshotting VM ${computeServer.externalId}")
            def resetResults = morpheus.executeCommandOnServer(computeServer,
                'sudo rm -f /etc/cloud/cloud.cfg.d/99-manual-cache.cfg; sudo cp /etc/machine-id /tmp/machine-id-old; sudo rm -f /etc/machine-id; sudo touch /etc/machine-id ; sync ; sync ; sleep 5'
            ).blockingGet()
            log.info("Reset Machine-Id results: ${resetResults}")
            sleep(30000) //ensure the reset flushes to disk before the snapshot is taken
        } catch(e) {
            log.error("resetCloudInitCache: ${e}", e)
        }
    }

    /**
     * Restores the source VM's original machine-id and re-enables cloud-init's cache-suppression marker
     * after the snapshot has been taken, so the live source VM's own cloud-init state is unaffected.
     */
    protected void restoreCloudInitCache(ComputeServer computeServer) {
        try {
            def restoreResults = morpheus.executeCommandOnServer(computeServer,
                "sudo bash -c \"echo 'manual_cache_clean: True' >> /etc/cloud/cloud.cfg.d/99-manual-cache.cfg ; cat /tmp/machine-id-old > /etc/machine-id ; rm /tmp/machine-id-old ; sleep 5 ; sync\""
            ).blockingGet()
            log.info("Restoring Machine-Id on original VM ${computeServer.externalId}: ${restoreResults}")
        } catch(e) {
            log.error("restoreCloudInitCache: ${e}", e)
        }
    }

    protected createSnapshot(vmId, Cloud cloud, Map connection = null) {
        def rtn = [:]
        HttpApiClient client
        try {
            if (!connection) {
                connection = OlvmComputeUtility.getToken(cloud, morpheus)
            }
            log.debug("createSnapshot: ${vmId}")
            def snapShotResult = OlvmComputeUtility.snapshotVm([
                connection:connection, vmId:vmId, description:"Vm (${vmId}) snapshot on ${new Date()}"
            ])
            def snapshot = snapShotResult.data
            rtn.snapshotId = snapshot.id
            rtn.status = snapshot.status
            rtn.totalSize = 0l
            rtn.disks = []

            // grab all the disk info for the VM snapshot
            def headers = OlvmComputeUtility.getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:true)
            client = OlvmComputeUtility.getApiClient(connection)
            def response = client.callJsonApi(
                connection.apiUrl,
                "${snapshot.href}/disks",
                reqOptions,
                'GET'
            )

            for (disk in response.data?['disk']) {
                rtn.disks << [
                    id:disk.id,
                    imageId:disk['image_id'],
                    storageDomainId:disk['storage_domains']['storage_domain'].first().id,
                    name:disk.name,
                    provisionedSize:disk['provisioned_size'].toLong()
                ]
                rtn.totalSize += disk['provisioned_size'].toLong()
            }

            rtn.success = true
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }

    protected deleteSnapshot(String vmId, String snapshotId, Cloud cloud, Map connection = null) {
        def rtn = ServiceResponse.success()
        HttpApiClient client
        try {
            if (!connection) {
                connection = OlvmComputeUtility.getToken(cloud, morpheus)
            }
            def headers = OlvmComputeUtility.getAuthenticatedBaseHeaders(connection)
            def reqOptions = new HttpApiClient.RequestOptions(headers:headers, ignoreSSL:true)
            client = OlvmComputeUtility.getApiClient(connection)
            def response = client.callJsonApi(
                connection.apiUrl,
                "/ovirt-engine/api/vms/${vmId}/snapshots/${snapshotId}".toString(),
                reqOptions,
                'DELETE'
            )

            if (!response.success && response.errorCode != '404') {
                throw new RuntimeException("Failed to delete snapshot ${snapshotId}: ${OlvmComputeUtility.extractErrorMessage(response.data)}")
            }

            log.debug("deleted snapshot ${snapshotId}")
        }
        catch (Throwable t) {
            rtn.success = false
            log.error("Failed to delete snapshot ${snapshotId}: ${t.message}", t)
        }
        finally {
            client?.shutdownClient()
        }
        return rtn
    }
}
