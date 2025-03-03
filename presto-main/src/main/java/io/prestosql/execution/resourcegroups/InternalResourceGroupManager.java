/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.execution.resourcegroups;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.node.NodeInfo;
import io.prestosql.execution.ManagedQueryExecution;
import io.prestosql.metadata.InternalNodeManager;
import io.prestosql.server.ResourceGroupInfo;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.memory.ClusterMemoryPoolManager;
import io.prestosql.spi.resourcegroups.ResourceGroupConfigurationManager;
import io.prestosql.spi.resourcegroups.ResourceGroupConfigurationManagerContext;
import io.prestosql.spi.resourcegroups.ResourceGroupConfigurationManagerFactory;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.spi.resourcegroups.SelectionContext;
import io.prestosql.spi.resourcegroups.SelectionCriteria;
import io.prestosql.statestore.StateStoreProvider;
import io.prestosql.utils.DistributedResourceGroupUtils;
import io.prestosql.utils.HetuConfig;
import org.weakref.jmx.JmxException;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.Managed;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.configuration.ConfigurationLoader.loadPropertiesFrom;
import static io.prestosql.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.prestosql.spi.StandardErrorCode.QUERY_REJECTED;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

@ThreadSafe
public final class InternalResourceGroupManager<C>
        implements ResourceGroupManager<C>
{
    private static final Logger log = Logger.get(InternalResourceGroupManager.class);
    private static final File RESOURCE_GROUPS_CONFIGURATION = new File("etc/resource-groups.properties");
    private static final String CONFIGURATION_MANAGER_PROPERTY_NAME = "resource-groups.configuration-manager";
    private static final String RESOURCE_GROUP_MEMORY_MARGIN_PERCENT = "resource-groups.memory-margin-percent";
    private static final String RESOURCE_GROUP_QUERY_PROGRESS_MARGIN_PERCENT = "resource-groups.query-progress-margin-percent";
    // default status refresh interval
    private static final long DEFAULT_STATUS_REFRESH_INTERVAL = 1L;
    private static final long MILLISECONDS_PER_TEN_SECONDS = 10000L;

    private final ScheduledExecutorService refreshExecutor = newSingleThreadScheduledExecutor(daemonThreadsNamed("ResourceGroupManager"));
    private final List<BaseResourceGroup> rootGroups = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<ResourceGroupId, BaseResourceGroup> groups = new ConcurrentHashMap<>();
    private final AtomicReference<ResourceGroupConfigurationManager<C>> configurationManager;
    private final ResourceGroupConfigurationManagerContext configurationManagerContext;
    private final ResourceGroupConfigurationManager<?> legacyManager;
    private final MBeanExporter exporter;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong lastCpuQuotaGenerationNanos = new AtomicLong(System.nanoTime());
    private final Map<String, ResourceGroupConfigurationManagerFactory> configurationManagerFactories = new ConcurrentHashMap<>();
    // status refresh interval
    private final long statusRefreshInterval;
    private final boolean isMultiCoordinatorEnabled;
    private final StateStoreProvider stateStoreProvider;
    private int memoryMarginPercent;
    private int queryProgressMarginPercent;
    private InternalNodeManager internalNodeManager;

    @Inject
    public InternalResourceGroupManager(LegacyResourceGroupConfigurationManager legacyManager,
            StateStoreProvider stateStoreProvider,
            ClusterMemoryPoolManager memoryPoolManager,
            NodeInfo nodeInfo,
            MBeanExporter exporter,
            HetuConfig hetuConfig,
            InternalNodeManager internalNodeManager)
    {
        this.exporter = requireNonNull(exporter, "exporter is null");
        this.configurationManagerContext = new ResourceGroupConfigurationManagerContextInstance(memoryPoolManager, nodeInfo.getEnvironment());
        this.legacyManager = requireNonNull(legacyManager, "legacyManager is null");
        this.configurationManager = new AtomicReference<>(cast(legacyManager));
        // check if multiple coordinators is enabled
        this.statusRefreshInterval = getStatusRefreshInterval(hetuConfig);
        this.isMultiCoordinatorEnabled = hetuConfig.isMultipleCoordinatorEnabled();
        this.stateStoreProvider = requireNonNull(stateStoreProvider, "stateStoreProvider is null");
        this.memoryMarginPercent = 10;
        this.queryProgressMarginPercent = 5;
        this.internalNodeManager = internalNodeManager;
    }

    @Override
    public ResourceGroupInfo getResourceGroupInfo(ResourceGroupId id)
    {
        checkArgument(groups.containsKey(id), "Group %s does not exist", id);
        return groups.get(id).getFullInfo();
    }

    @Override
    public List<ResourceGroupInfo> getPathToRoot(ResourceGroupId id)
    {
        checkArgument(groups.containsKey(id), "Group %s does not exist", id);
        return groups.get(id).getPathToRoot();
    }

    @Override
    public void submit(ManagedQueryExecution queryExecution, SelectionContext<C> selectionContext, Executor executor)
    {
        checkState(configurationManager.get() != null, "configurationManager not set");
        createGroupIfNecessary(selectionContext, executor);
        // Update shared resource group states before submitting new query
        if (isMultiCoordinatorEnabled) {
            DistributedResourceGroupUtils.mapCachedStates();
            BaseResourceGroup currentRoot = groups.get(selectionContext.getResourceGroupId().getRoot());
            checkState(currentRoot != null, "currentRoot should not be null");
            synchronized (currentRoot) {
                Lock lock = stateStoreProvider.getStateStore().getLock(selectionContext.getResourceGroupId().toString());
                boolean locked = false;
                try {
                    locked = Thread.holdsLock(lock) || lock.tryLock(MILLISECONDS_PER_TEN_SECONDS, TimeUnit.MILLISECONDS);
                    if (locked) {
                        groups.get(selectionContext.getResourceGroupId()).run(queryExecution);
                    }
                    else {
                        throw new PrestoException(GENERIC_INTERNAL_ERROR,
                                String.format("Query: %s submitted failed! Coordinator probably too busy, please try again later",
                                        queryExecution.getBasicQueryInfo().getQueryId()));
                    }
                }
                catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                finally {
                    if (locked) {
                        lock.unlock();
                    }
                }
            }
        }
        else {
            groups.get(selectionContext.getResourceGroupId()).run(queryExecution);
        }
    }

    @Override
    public SelectionContext<C> selectGroup(SelectionCriteria criteria)
    {
        return configurationManager.get().match(criteria)
                .orElseThrow(() -> new PrestoException(QUERY_REJECTED, "Query did not match any selection rule"));
    }

    @Override
    public void addConfigurationManagerFactory(ResourceGroupConfigurationManagerFactory factory)
    {
        if (configurationManagerFactories.putIfAbsent(factory.getName(), factory) != null) {
            throw new IllegalArgumentException(format("Resource group configuration manager '%s' is already registered", factory.getName()));
        }
    }

    @Override
    public void loadConfigurationManager()
            throws Exception
    {
        if (RESOURCE_GROUPS_CONFIGURATION.exists()) {
            Map<String, String> properties = new HashMap<>(loadPropertiesFrom(RESOURCE_GROUPS_CONFIGURATION.getPath()));

            String configurationManagerName = properties.remove(CONFIGURATION_MANAGER_PROPERTY_NAME);
            checkArgument(!isNullOrEmpty(configurationManagerName),
                    "Resource groups configuration %s does not contain %s", RESOURCE_GROUPS_CONFIGURATION.getAbsoluteFile(), CONFIGURATION_MANAGER_PROPERTY_NAME);

            memoryMarginPercent = Integer.valueOf(properties.getOrDefault(RESOURCE_GROUP_MEMORY_MARGIN_PERCENT, "10"));
            properties.remove(RESOURCE_GROUP_MEMORY_MARGIN_PERCENT);
            queryProgressMarginPercent = Integer.valueOf(properties.getOrDefault(RESOURCE_GROUP_QUERY_PROGRESS_MARGIN_PERCENT, "5"));
            properties.remove(RESOURCE_GROUP_QUERY_PROGRESS_MARGIN_PERCENT);
            setConfigurationManager(configurationManagerName, properties);
        }
    }

    @VisibleForTesting
    public void setConfigurationManager(String name, Map<String, String> properties)
    {
        requireNonNull(name, "name is null");
        requireNonNull(properties, "properties is null");

        log.info("-- Loading resource group configuration manager --");

        ResourceGroupConfigurationManagerFactory configurationManagerFactory = configurationManagerFactories.get(name);
        checkState(configurationManagerFactory != null, "Resource group configuration manager %s is not registered", name);

        ResourceGroupConfigurationManager<C> configurationManager = cast(configurationManagerFactory.create(ImmutableMap.copyOf(properties), configurationManagerContext));
        checkState(this.configurationManager.compareAndSet(cast(legacyManager), configurationManager), "configurationManager already set");

        log.info("-- Loaded resource group configuration manager %s --", name);
    }

    @SuppressWarnings("ObjectEquality")
    @VisibleForTesting
    public ResourceGroupConfigurationManager<C> getConfigurationManager()
    {
        ResourceGroupConfigurationManager<C> manager = configurationManager.get();
        checkState(manager != legacyManager, "cannot fetch legacy manager");
        return manager;
    }

    @PreDestroy
    public void destroy()
    {
        refreshExecutor.shutdownNow();
    }

    @PostConstruct
    public void start()
    {
        if (started.compareAndSet(false, true)) {
            refreshExecutor.scheduleWithFixedDelay(this::refreshAndStartQueries, statusRefreshInterval, statusRefreshInterval, TimeUnit.MILLISECONDS);
        }
    }

    private void refreshAndStartQueries()
    {
        if (isMultiCoordinatorEnabled) {
            try {
                DistributedResourceGroupUtils.mapCachedStates();
            }
            catch (RuntimeException e) {
                log.error("Error mapCachedStates: " + e.getMessage());
            }
        }

        //for both single and multiple coordinator
        long nanoTime = System.nanoTime();
        long elapsedSeconds = NANOSECONDS.toSeconds(nanoTime - lastCpuQuotaGenerationNanos.get());
        if (elapsedSeconds > 0) {
            // Only advance our clock on second boundaries to avoid calling generateCpuQuota() too frequently, and because it would be a no-op for zero seconds.
            lastCpuQuotaGenerationNanos.addAndGet(elapsedSeconds * 1_000_000_000L);
        }
        else if (elapsedSeconds < 0) {
            // nano time has overflowed
            lastCpuQuotaGenerationNanos.set(nanoTime);
        }
        for (BaseResourceGroup group : rootGroups) {
            try {
                if (elapsedSeconds > 0) {
                    group.generateCpuQuota(elapsedSeconds);
                }
                group.processQueuedQueries();
            }
            catch (RuntimeException e) {
                log.error(e, "Exception while refreshing for group %s", group);
            }
        }
    }

    private synchronized void createGroupIfNecessary(SelectionContext<C> context, Executor executor)
    {
        ResourceGroupId id = context.getResourceGroupId();
        if (!groups.containsKey(id)) {
            BaseResourceGroup group;
            if (id.getParent().isPresent()) {
                createGroupIfNecessary(configurationManager.get().parentGroupContext(context), executor);
                BaseResourceGroup parent = groups.get(id.getParent().get());
                requireNonNull(parent, "parent is null");
                group = parent.getOrCreateSubGroup(id.getLastSegment());
            }
            else {
                BaseResourceGroup root = createNewRootGroup(id.getSegments().get(0), executor);
                root.setMemoryMarginPercent(memoryMarginPercent);
                root.setQueryProgressMarginPercent(queryProgressMarginPercent);
                group = root;
                rootGroups.add(root);
            }
            configurationManager.get().configure(group, context);
            checkState(groups.put(id, group) == null, "Unexpected existing resource group");
        }
    }

    private void exportGroup(BaseResourceGroup group, Boolean export)
    {
        try {
            if (export) {
                exporter.exportWithGeneratedName(group, BaseResourceGroup.class, group.getId().toString());
            }
            else {
                exporter.unexportWithGeneratedName(BaseResourceGroup.class, group.getId().toString());
            }
        }
        catch (JmxException e) {
            log.error(e, "Error %s resource group %s", export ? "exporting" : "unexporting", group.getId());
        }
    }

    @Managed
    public int getQueriesQueuedOnInternal()
    {
        int queriesQueuedInternal = 0;
        for (BaseResourceGroup rootGroup : rootGroups) {
            synchronized (rootGroup) {
                queriesQueuedInternal += getQueriesQueuedOnInternal(rootGroup);
            }
        }

        return queriesQueuedInternal;
    }

    private static int getQueriesQueuedOnInternal(BaseResourceGroup resourceGroup)
    {
        if (resourceGroup.subGroups().isEmpty()) {
            return Math.min(resourceGroup.getQueuedQueries(), resourceGroup.getSoftConcurrencyLimit() - resourceGroup.getRunningQueries());
        }

        int queriesQueuedInternal = 0;
        for (BaseResourceGroup subGroup : (Collection<BaseResourceGroup>) resourceGroup.subGroups()) {
            queriesQueuedInternal += getQueriesQueuedOnInternal(subGroup);
        }

        return queriesQueuedInternal;
    }

    @SuppressWarnings("unchecked")
    private static <C> ResourceGroupConfigurationManager<C> cast(ResourceGroupConfigurationManager<?> manager)
    {
        return (ResourceGroupConfigurationManager<C>) manager;
    }

    /**
     * Get different status refresh intervals based on if multiple coordinators is enabled
     *
     * @param hetuConfig Hetu configs
     * @return status refresh interval
     */
    private static long getStatusRefreshInterval(HetuConfig hetuConfig)
    {
        requireNonNull(hetuConfig, "hetuConfig is null");
        if (hetuConfig.isMultipleCoordinatorEnabled()) {
            return hetuConfig.getStateFetchInterval().toMillis();
        }
        else {
            return DEFAULT_STATUS_REFRESH_INTERVAL;
        }
    }

    /**
     * Create root resource groups based on if multiple coordinators is enabled
     *
     * @param name Resource group name
     * @param executor Executor service for submitting queries
     * @return created root resource group
     */
    private BaseResourceGroup createNewRootGroup(String name, Executor executor)
    {
        if (isMultiCoordinatorEnabled) {
            return new DistributedResourceGroupTemp(Optional.empty(), name, this::exportGroup, executor, stateStoreProvider.getStateStore(), internalNodeManager);
        }
        else {
            return new InternalResourceGroup(Optional.empty(), name, this::exportGroup, executor);
        }
    }

    @Override
    public long getCachedMemoryUsage(ResourceGroupId resourceGroupId)
    {
        return groups.get(resourceGroupId).getCachedMemoryUsageBytes();
    }

    @Override
    public long getSoftReservedMemory(ResourceGroupId resourceGroupId)
    {
        return groups.get(resourceGroupId).getSoftReservedMemory().toBytes();
    }

    @Override
    public boolean isGroupRegistered(ResourceGroupId resourceGroupId)
    {
        return groups.containsKey(resourceGroupId);
    }
}
