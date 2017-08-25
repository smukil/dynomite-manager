package com.netflix.dynomitemanager.dynomite;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.dynomitemanager.config.FloridaConfig;
import com.netflix.dynomitemanager.storage.StorageProxy;
import com.netflix.nfsidecar.identity.IInstanceState;
import com.netflix.nfsidecar.identity.InstanceIdentity;
import com.netflix.nfsidecar.instance.InstanceDataRetriever;
import com.netflix.nfsidecar.resources.env.IEnvVariables;
import com.netflix.nfsidecar.utils.ProcessTuner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Singleton
public class DynomiteStandardTuner implements ProcessTuner {
    private static final Logger logger = LoggerFactory.getLogger(DynomiteStandardTuner.class);
    private static final String ROOT_NAME = "dyn_o_mite";
    public static final long GB_2_IN_KB = 2L * 1024L * 1024L;

    protected final FloridaConfig config;
    protected final InstanceIdentity ii;
    protected final StorageProxy storageProxy;
    protected final IInstanceState instanceState;
    protected final IEnvVariables envVariables;
    protected final InstanceDataRetriever instanceDataRetriever;

    public static final Pattern MEMINFO_PATTERN = Pattern.compile("MemTotal:\\s*([0-9]*)");

    @Inject
    public DynomiteStandardTuner(FloridaConfig config, InstanceIdentity ii, IInstanceState instanceState,
            StorageProxy storageProxy, IEnvVariables envVariables, InstanceDataRetriever instanceDataRetriever) {
        this.config = config;
        this.ii = ii;
        this.instanceState = instanceState;
        this.storageProxy = storageProxy;
        this.envVariables = envVariables;
        this.instanceDataRetriever = instanceDataRetriever;

    }

    /**
     * This is a wrapper around the FP of the max allocated messages. Message
     * allocation is based on the instancy type 2GB for Florida + 85% for Redis
     */

    public int setMaxMsgs() {
        if (config.getDynomiteMaxAllocatedMessages() == 0) {

            String instanceType = this.instanceDataRetriever.getInstanceType();

            if (instanceType.contains(".xlarge")) {
                // r3.xlarge: 30.5GB RAM (2.5GB available)
                logger.info("Instance Type: " + instanceType + " ---> " + " Max Msgs: " + 100000);
                return 100000;
            } else if (instanceType.contains(".2xlarge")) {
                // r3.2xlarge: 61GB RAM (7.15GB available)
                logger.info("Instance Type: " + instanceType + " ---> " + " Max Msgs: " + 300000);
                return 300000;
            } else if (instanceType.contains(".4xlarge")) {
                // r3.4xlarge: 122GB RAM (16.3GB available)
                logger.info("Instance Type: " + instanceType + " ---> " + " Max Msgs: " + 800000);
                return 800000;
            } else if (instanceType.contains(".8xlarge")) {
                // r3.8xlarge: 244GB RAM (34.19GB available)
                logger.info("Instance Type: " + instanceType + " ---> " + " Max Msgs: " + 1000000);
                return 1000000;
            } else
                return 500000;

        }
        return config.getDynomiteMaxAllocatedMessages();
    }

    /**
     * we want to throw the exception for higher layer to handle it.
     */
    public void writeAllProperties(String yamlLocation) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        File yamlFile = new File(yamlLocation);
        Map map = (Map) yaml.load(new FileInputStream(yamlFile));
        Map<String, Object> entries = (Map) map.get(ROOT_NAME);
        entries.clear();

        entries.put("auto_eject_hosts", config.getDynomiteAutoEjectHosts());
        entries.put("rack", envVariables.getRack());
        entries.put("distribution", config.getDistribution());
        entries.put("dyn_listen", "0.0.0.0:" + config.getStoragePeerPort());
        entries.put("dyn_seed_provider", config.getDynomiteSeedProvider());
        entries.put("gos_interval", config.getDynomiteGossipInterval());
        entries.put("hash", config.getDynomiteHashAlgorithm());
        entries.put("listen", "0.0.0.0:" + config.getDynomiteClientPort());
        entries.put("preconnect", config.getDynomiteStoragePreconnect());
        entries.put("server_retry_timeout", config.getServerRetryTimeout());
        entries.put("timeout", config.getTimeout());
        entries.put("tokens", ii.getTokens());
        entries.put("secure_server_option", config.getDynomiteIntraClusterSecurity());
        entries.remove("redis");
        entries.put("datacenter", envVariables.getRegion());
        entries.put("read_consistency", config.getDynomiteReadConsistency());
        entries.put("write_consistency", config.getDynomiteWriteConsistency());
        entries.put("mbuf_size", config.getDynomiteMBufSize());
        entries.put("max_msgs", setMaxMsgs());
        entries.put("pem_key_file", config.getDynomiteInstallDir() + "/conf/dynomite.pem");

        List<String> seedp = (List) entries.get("dyn_seeds");
        if (seedp == null) {
            seedp = new ArrayList<String>();
            entries.put("dyn_seeds", seedp);
        } else {
            seedp.clear();
        }

        List<String> seeds = ii.getSeeds();
        if (seeds.size() != 0) {
            for (String seed : seeds) {
                seedp.add(seed);
            }
        } else {
            entries.remove("dyn_seeds");
        }

        List<String> servers = (List) entries.get("servers");
        if (servers == null) {
            servers = new ArrayList<String>();
            entries.put("servers", servers);
        } else {
            servers.clear();
        }

        entries.put("data_store", storageProxy.getEngineNumber());
        if (!storageProxy.getUnixPath().equals("")) {
            servers.add(storageProxy.getUnixPath() + ":1");
        } else {
            servers.add(storageProxy.getIpAddress() + ":" + storageProxy.getPort() + ":1");
        }

        if (config.getConnectionPoolEnabled()) {
            entries.put("datastore_connections", config.getDatastoreConnections());
            entries.put("local_peer_connections", config.getLocalPeerConnections());
            entries.put("remote_peer_connections", config.getRemotePeerConnections());
        }

        if (!this.instanceState.getYmlWritten()) {
            logger.info("YAML Dump: ");
            logger.info(yaml.dump(map));
            storageProxy.updateConfiguration();
        } else {
            logger.info("Updating dynomite.yml with latest information");
        }
        yaml.dump(map, new FileWriter(yamlLocation));

        this.instanceState.setYmlWritten(true);

    }

    @SuppressWarnings("unchecked")
    public void updateAutoBootstrap(String yamlFile, boolean autobootstrap) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);
        @SuppressWarnings("rawtypes")
        Map map = (Map) yaml.load(new FileInputStream(yamlFile));
        // Dont bootstrap in restore mode
        map.put("auto_bootstrap", autobootstrap);
        logger.info("Updating yaml" + yaml.dump(map));
        yaml.dump(map, new FileWriter(yamlFile));
    }

}