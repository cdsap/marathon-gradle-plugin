package com.malinskiy.marathon.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidTest
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.BuiltArtifactsLoader
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.TestAndroidComponentsExtension
import com.malinskiy.marathon.config.Configuration
import com.malinskiy.marathon.config.vendor.VendorConfiguration
import com.malinskiy.marathon.gradle.configuration.toStrategy
import com.malinskiy.marathon.gradle.service.JsonService
import com.malinskiy.marathon.gradle.task.GenerateMarathonfileTask
import com.malinskiy.marathon.gradle.task.MarathonRunTask
import com.malinskiy.marathon.gradle.task.MarathonUnpackTask
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RelativePath
import org.gradle.api.logging.Logger
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.closureOf
import org.gradle.kotlin.dsl.findByType
import java.io.File

class MarathonPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val logger = project.logger
        logger.info("Applying marathon plugin")
        val marathonExtension = project.extensions.create("marathon", MarathonExtension::class.java)

        // Register shared service on current project's gradle instance (not rootProject)
        val jsonServiceProvider = project.gradle.sharedServices.registerIfAbsent("marathonJson", JsonService::class.java) {}

        // Register wrapper task on current project instead of rootProject
        val wrapper: TaskProvider<MarathonUnpackTask> = project.tasks.findByName(MarathonUnpackTask.NAME)?.let {
            project.tasks.named(MarathonUnpackTask.NAME, MarathonUnpackTask::class.java)
        } ?: applyOnProject(project)

        val marathonTask: Task = project.task(TASK_PREFIX, closureOf<Task> {
            group = JavaBasePlugin.VERIFICATION_GROUP
            description = "Runs all the instrumentation test variations on all the connected devices"
        })

        val appExtension = project.extensions.findByType(ApplicationAndroidComponentsExtension::class.java)
        val libraryExtension = project.extensions.findByType(LibraryAndroidComponentsExtension::class.java)
        val testExtension = project.extensions.findByType(TestAndroidComponentsExtension::class.java)
        val conf =
            project.extensions.getByName("marathon") as? MarathonExtension ?: throw IllegalStateException("Android extension not found")

        when {
            appExtension != null -> {
                val sdkDirectory: Provider<Directory> = appExtension.sdkComponents.sdkDirectory
                appExtension.onVariants { applicationVariant ->
                    val androidTest = applicationVariant.androidTest
                    if (androidTest != null) {
                        logger.info("Applying marathon for ${applicationVariant.name}")

                        val apkFolder: Provider<Directory> = applicationVariant.artifacts.get(SingleArtifact.APK)
                        val artifactsLoader = applicationVariant.artifacts.getBuiltArtifactsLoader()

                        val testApkFolder: Provider<Directory> = androidTest.artifacts.get(SingleArtifact.APK)
                        val testArtifactsLoader = androidTest.artifacts.getBuiltArtifactsLoader()

                        val bundle = GradleAndroidTestBundle.ApplicationWithTest(
                            apkFolder = project.objects.directoryProperty().apply { set(apkFolder) },
                            artifactLoader = project.objects.property(BuiltArtifactsLoader::class.java)
                                .apply { set(artifactsLoader) },
                            testApkFolder = project.objects.directoryProperty().apply { set(testApkFolder) },
                            testArtifactLoader = project.objects.property(BuiltArtifactsLoader::class.java)
                                .apply { set(testArtifactsLoader) },
                        )

                        val (generateMarathonfileTaskProvider, testTaskForVariantProvider) = createTasks(
                            logger, androidTest.name, bundle, project, conf, sdkDirectory, wrapper, jsonServiceProvider
                        )
                        marathonTask.dependsOn(testTaskForVariantProvider)
                    }
                }
            }

            libraryExtension != null -> {
                val sdkDirectory: Provider<Directory> = libraryExtension.sdkComponents.sdkDirectory
                libraryExtension.onVariants { libraryVariant ->
                    val androidTest = libraryVariant.androidTest
                    if (androidTest != null) {
                        logger.info("Applying marathon for ${libraryVariant.name}")

                        val testApkFolder: Provider<Directory> = androidTest.artifacts.get(SingleArtifact.APK)
                        val testArtifactsLoader = androidTest.artifacts.getBuiltArtifactsLoader()

                        val bundle = GradleAndroidTestBundle.TestOnly(
                            testApkFolder = project.objects.directoryProperty().apply { set(testApkFolder) },
                            testArtifactLoader = project.objects.property(BuiltArtifactsLoader::class.java)
                                .apply { set(testArtifactsLoader) },
                        )

                        val (generateMarathonfileTask, testTaskForVariant) = createTasks(
                            logger, androidTest.name, bundle, project, conf, sdkDirectory, wrapper, jsonServiceProvider
                        )
                        marathonTask.dependsOn(testTaskForVariant)
                    }
                }
            }

            testExtension != null -> {
                val sdkDirectory: Provider<Directory> = testExtension.sdkComponents.sdkDirectory
                testExtension.onVariants { androidTest ->
                    logger.info("Applying marathon for ${androidTest.name}")

                    val testApkFolder: Provider<Directory> = androidTest.artifacts.get(SingleArtifact.APK)
                    val testArtifactsLoader = androidTest.artifacts.getBuiltArtifactsLoader()

                    val bundle = GradleAndroidTestBundle.TestOnly(
                        testApkFolder = project.objects.directoryProperty().apply { set(testApkFolder) },
                        testArtifactLoader = project.objects.property(BuiltArtifactsLoader::class.java)
                            .apply { set(testArtifactsLoader) },
                    )

                    val (_, testTaskForVariant) = createTasks(
                        logger, androidTest.name, bundle, project, conf, sdkDirectory, wrapper, jsonServiceProvider
                    )
                    marathonTask.dependsOn(testTaskForVariant)

                }
            }

            else -> throw IllegalStateException("No AndroidComponentsExtensions found. Did you apply marathon plugin after applying the application/library plugin?")
        }
    }

    private fun applyOnProject(project: Project): TaskProvider<MarathonUnpackTask> {
        val distZip = project.objects.fileProperty()
        distZip.set(project.layout.buildDirectory.dir("marathon").map { it.file("marathon-cli.zip") })

        val distZipTaskProvider = project.tasks.register("marathonWrapperExtract", Copy::class.java) {
            inputs.property("md5", DigestUtils.md5Hex(MarathonPlugin::class.java.getResourceAsStream(CLI_PATH)))
            outputs.file(distZip).withPropertyName("distZip")
            from(project.zipTree(File(MarathonPlugin::class.java.protectionDomain.codeSource.location.toURI()).path))
            include("marathon-cli.zip")
            into(project.layout.buildDirectory.dir("marathon"))
        }

        val wrapperTask = project.tasks.register(MarathonUnpackTask.NAME, MarathonUnpackTask::class.java) {
            inputs.file(distZipTaskProvider.map { File(it.destinationDir, "marathon-cli.zip") })
                .withPropertyName("distZip")
            dist.set(project.layout.buildDirectory.dir("marathon").map { it.dir("cli") })

            from(project.zipTree(distZip)) {
                eachFile {
                    relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
                }
                includeEmptyDirs = false
            }
            into(dist)
        }

        val cleanTaskProvider = project.tasks.register("cleanMarathonWrapper", Delete::class.java) {
            group = Const.GROUP
            setDelete(project.layout.buildDirectory.dir("marathon"))
        }
        project.plugins.withType(BasePlugin::class.java) {
            project.tasks.named(BasePlugin.CLEAN_TASK_NAME).configure {
                dependsOn(cleanTaskProvider)
            }
        }
        return wrapperTask
    }

    companion object {
        private fun createTasks(
            logger: Logger,
            variantName: String,
            bundle: GradleAndroidTestBundle,
            project: Project,
            config: MarathonExtension,
            sdkDirectory: Provider<Directory>,
            wrapper: TaskProvider<MarathonUnpackTask>,
            jsonServiceProvider: Provider<JsonService>,
        ): Pair<TaskProvider<GenerateMarathonfileTask>, TaskProvider<MarathonRunTask>> {
            val baseOutputDir = config.baseOutputDir?.let { File(it) } ?: project.layout.buildDirectory.dir("reports/marathon").get().asFile
            val output = File(baseOutputDir, variantName)

            val configurationBuilder = Configuration.Builder(config.name, output).apply {
                config.analyticsConfiguration?.toAnalyticsConfiguration()?.let { analyticsConfiguration = it }
                config.poolingStrategy?.toStrategy()?.let { poolingStrategy = it }
                config.shardingStrategy?.toStrategy()?.let { shardingStrategy = it }
                config.sortingStrategy?.toStrategy()?.let { sortingStrategy = it }
                config.batchingStrategy?.toStrategy()?.let { batchingStrategy = it }
                config.flakinessStrategy?.toStrategy()?.let { flakinessStrategy = it }
                config.retryStrategy?.toStrategy()?.let { retryStrategy = it }
                config.filteringConfiguration?.toFilteringConfiguration()?.let { filteringConfiguration = it }
                config.ignoreFailures?.let { ignoreFailures = it }
                config.isCodeCoverageEnabled?.let { isCodeCoverageEnabled = it }
                config.executionStrategy?.let { executionStrategy = it }
                config.uncompletedTestRetryQuota?.let { uncompletedTestRetryQuota = it }
                config.includeSerialRegexes?.map { it.toRegex() }?.let { includeSerialRegexes = it }
                config.excludeSerialRegexes?.map { it.toRegex() }?.let { excludeSerialRegexes = it }
                config.testBatchTimeoutMillis?.let { testBatchTimeoutMillis = it }
                config.testOutputTimeoutMillis?.let { testOutputTimeoutMillis = it }
                config.debug?.let { debug = it }
                config.screenRecordingPolicy?.let { screenRecordingPolicy = it }
                config.analyticsTracking?.let { analyticsTracking = it }
                config.bugsnagReporting?.let { bugsnagReporting = it }
                config.deviceInitializationTimeoutMillis?.let {
                    deviceInitializationTimeoutMillis = deviceInitializationTimeoutMillis
                }
                config.outputConfiguration?.toStrategy()?.let { outputConfiguration = it }
            }
            val vendorConfigurationBuilder = VendorConfiguration.AndroidConfigurationBuilder().apply {
                config.autoGrantPermission?.let { autoGrantPermission = it }
                instrumentationArgs = config.instrumentationArgs
                config.applicationPmClear?.let { applicationPmClear = it }
                config.testApplicationPmClear?.let { testApplicationPmClear = it }
                config.adbInitTimeout?.let { adbInitTimeoutMillis = it }
                config.installOptions?.let { installOptions = it }
                config.screenRecordConfiguration?.let { screenRecordConfiguration = it }
                config.serialStrategy?.let { serialStrategy = it }
                config.waitForDevicesTimeoutMillis?.let { waitForDevicesTimeoutMillis = it }
                config.allureConfiguration?.let { allureConfiguration = it }
                config.fileSyncConfiguration?.let { fileSyncConfiguration = it }
                config.testParserConfiguration?.let { testParserConfiguration = it }
                config.testAccessConfiguration?.let { testAccessConfiguration = it }
                config.timeoutConfiguration?.let { timeoutConfiguration = it }
                config.adbServers?.let { adbServers = it }
                config.disableWindowAnimation?.let { disableWindowAnimation = it }
            }

            val jsonService = jsonServiceProvider.get()
            val configurationJson = jsonService.serialize(configurationBuilder)
            val vendorConfigurationJson = jsonService.serialize(vendorConfigurationBuilder)

            val generateMarathonfileTask =
                project.tasks.register(
                    "$TASK_PREFIX${variantName.capitalize()}GenerateMarathonfile",
                    GenerateMarathonfileTask::class.java
                ) {
                    group = Const.GROUP
                    description = "Generates Marathonfile for '${variantName}' variation"
                    flavorName.set(variantName)
                    applicationBundle.set(listOf(bundle))
                    this.configurationBuilder.set(configurationJson)
                    this.vendorConfigurationBuilder.set(vendorConfigurationJson)
                    this.jsonService.set(jsonServiceProvider)
                    sdk.set(sdkDirectory)
                    sdkPath.set(sdkDirectory.map { dir -> dir.asFile.name })
                    marathonfile.set(project.layout.buildDirectory.dir("marathon").map { it.dir(variantName) }
                                         .map { it.file("Marathonfile") })
                }

            val marathonTask = project.tasks.register("$TASK_PREFIX${variantName.capitalize()}", MarathonRunTask::class.java) {
                group = JavaBasePlugin.VERIFICATION_GROUP
                description = "Runs instrumentation tests on all the connected devices for '${variantName}' " +
                    "variation and generates a report with screenshots"
                outputs.upToDateWhen { false }
                dist.set(wrapper.flatMap { it.dist })
                marathonfile.set(generateMarathonfileTask.flatMap { it.marathonfile })
            }


            return Pair(generateMarathonfileTask, marathonTask)
        }


        /**
         * Task name prefix.
         */
        private const val TASK_PREFIX = "marathon"
        private const val CLI_PATH = "/marathon-cli.zip"

    }
}
