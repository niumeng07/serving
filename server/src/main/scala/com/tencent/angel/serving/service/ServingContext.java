package com.tencent.angel.serving.service;

class ServingContext(eventBus: EventBus[ServableState],
                     monitor: ServableStateMonitor,
                     totalResources: ResourceAllocation,
                     platformConfigMap: PlatformConfigMap) extends CoreContext(
                       eventBus, monitor, totalResources, platformConfigMap) {

                       import ServingContext._

                       private val LOG = LogFactory.getLog(classOf[ServingContext])

                       private val serverRequestLogger: ServerRequestLogger = _
                       private val platform2RouterPort = new mutable.HashMap[String, Int]()
                       private var storagePathSourceAndRouter: StoragePathSourceAndRouter = _


                       override def addModelsViaModelConfigList(config: ModelServerConfig): Unit = {
                         val isFirstConfig = storagePathSourceAndRouter == null
                         val sourceConfig = createStoragePathSourceConfig(config)
                         val routes = createStoragePathRoutes(config)

                         if (isFirstConfig) {
                           val adapters = createAdapters()
                           val router = createRouter(routes, adapters)
                           val source = createStoragePathSource(sourceConfig, router)

                           // Connect the adapters to the manager, and wait for the models to load.
                           connectAdaptersToManagerAndAwaitModelLoads(adapters, config)

                           //store the source components
                           storagePathSourceAndRouter = StoragePathSourceAndRouter(source, router)

                         } else {
                           // Figure out which models are new.
                           val newModels = newModelNamesInSourceConfig(storagePathSourceAndRouter.source.config_, sourceConfig)

                           // Now we're ready to start reconfiguring the elements of the Source-> Manager pipeline ...

                           val oldAndNewRoutes = unionRoutes(storagePathSourceAndRouter.router.getRoutes, routes)
                           reloadRoutes(oldAndNewRoutes)
                           reloadStoragePathSourceConfig(sourceConfig)
                           reloadRoutes(routes)
                           waitUntilModelsAvailable(newModels, monitor) //todo: ServableStateMonitor
                         }
                       }

                       override def customModelConfigLoader: CustomModelConfigLoader = {
                         null.asInstanceOf[CustomModelConfigLoader]
                       }

                       override def maybeUpdateServerRequestLogger(configCase: ModelServerConfig.ConfigCase): Unit = ???

                       def createAspiredVersionsManager(policy: AspiredVersionPolicy): AspiredVersionsManager = ???

                       def createResourceTracker(): ResourceTracker = ???

                       def createAdapter(modelPlatform: String): StoragePathSourceAdapter = ???

                       def createStoragePathSourceConfig(config: ModelServerConfig): FileSystemStoragePathSourceConfig = {
                         val servables = new java.util.ArrayList[ServableToMonitor]()
                         config.getModelConfigList.getConfigList.asScala.foreach { model =>
                           LOG.info(s"(re-)adding model: ${model.getName}")
                           val monitorBuilder = ServableToMonitor.newBuilder()
                           monitorBuilder.setServableName(model.getName)
                           monitorBuilder.setBasePath(model.getBasePath)
                           monitorBuilder.setServableVersionPolicy(model.getModelVersionPolicy)
                           servables.add(monitorBuilder.build())
                         }
                         val builder = FileSystemStoragePathSourceConfig.newBuilder()
                         builder.addAllServables(servables)
                         builder.setFileSystemPollWaitSeconds(fileSystemPollWaitSeconds)
                         builder.setFailIfZeroVersionsAtStartup(failIfNoModelVersionsFound)
                         builder.build()
                       }

                       def createStoragePathRoutes(config: ModelServerConfig): Routes = {
                         config.getModelConfigList.getConfigList.asScala.map {
                           case model if platform2RouterPort.get(getPlatform(model)).isDefined =>
                             model.getName -> platform2RouterPort(getPlatform(model))
                           case model => throw NotFoundExceptions(s"port not find for model: ${model.getName}")
                         }.toMap
                       }

                       def createStoragePathSource(config: FileSystemStoragePathSourceConfig,
                                                   target: Target[StoragePath]): FileSystemStoragePathSource = {
                         val source = FileSystemStoragePathSource.create(config)
                         ConnectSourceToTarget(source, target)
                         source
                       }

                       def createRouter(routes: Routes, targets: SourceAdapters): DynamicSourceRouter[StoragePath] = {
                         val numOutpurPorts = targets.platformAdapters.size + 1
                         val router = DynamicSourceRouter[StoragePath](numOutpurPorts, routes)

                         val outputPorts: List[Source[StoragePath]] = router.getOutputPorts
                         targets.platformAdapters.foreach { case (platform, adapter) =>
                           val port: Option[Int] = platform2RouterPort.get(platform)
                           if (port.isEmpty) {
                             throw FailedPreconditions("Router port for platform not found.")
                           }
                           ConnectSourceToTarget(outputPorts(port.get), adapter)
                         }
                         ConnectSourceToTarget(outputPorts.last, targets.errorAdapter)

                         router
                       }

                       def createAdapters(): SourceAdapters = {
                         val platformAdapters = platform2RouterPort.map { case (platform, _) =>
                           val adapter: StoragePathSourceAdapter = createAdapter(platform)
                           platform -> adapter
                         }.toMap

                         val errorAdapters = new ErrorSourceAdapter[StoragePath, Loader](FailedPreconditions("No platform found for model"))
                         SourceAdapters(platformAdapters, errorAdapters)
                       }

                       def newModelNamesInSourceConfig(oldConfig: FileSystemStoragePathSourceConfig,
                                                       newConfig: FileSystemStoragePathSourceConfig): Set[String] = {
                         val oldModels = oldConfig.getServablesList.asScala.map { servable => servable.getServableName }.toSet

                         newConfig.getServablesList.asScala.filter { servable => !oldModels.contains(servable.getServableName) }
                           .map { servable => servable.getServableName }.toSet
                       }

                       def unionRoutes(routes1: Routes, routes2: Routes): Routes = {
                         // routes1 ++ routes2
                         var result: Routes = routes1
                         routes2.foreach { case (platform, port) =>
                           val port1 = routes1.get(platform)
                           if (port1.isEmpty) {
                             result += (platform -> port)
                           } else {
                             if (port1.get != port) {
                               throw InvalidArguments("Conflict while unioning two route maps.")
                             }
                           }
                         }
                         result
                       }

                       private def reloadRoutes(routes: Routes): Unit = {
                         try {
                           storagePathSourceAndRouter.router.updateRoutes(routes)
                         } catch {
                           case e: Exception => LOG(e.getMessage)
                         }
                       }

                       private def reloadStoragePathSourceConfig(sourceConfig: FileSystemStoragePathSourceConfig): Unit = {
                         try {
                           storagePathSourceAndRouter.source.updateConfig(sourceConfig)
                         } catch {
                           case e: Exception => LOG(e.getMessage)
                         }
                       }
                     }