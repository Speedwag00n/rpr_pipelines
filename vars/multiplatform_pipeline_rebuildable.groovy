import java.text.SimpleDateFormat;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import groovy.json.JsonBuilder
import jenkins.model.Jenkins


def getTestsName(String asicName, String osName, String testName) {
    return "${asicName}-${osName}-${testName}"
}


Map collectTestsOptions(Map options) {
    Map testsOptions = options.clone()
    //testsOptions.remove('rbs_dev')
    //testsOptions.remove('rbs_prod')
    Map masterEnv = [:]
    masterEnv['BUILD_NUMBER'] = env.BUILD_NUMBER
    masterEnv['BUILD_DISPLAY_NAME'] = env.BUILD_DISPLAY_NAME
    testsOptions['masterEnv'] = masterEnv

    return testsOptions
}


def executePlatform(String osName, String gpuNames, def executeBuild, Map options, Map testsBuildsIds) {
    def retNode = {
        try {
            if(!options['skipBuild'] && options['executeBuild'] && executeBuild) {
                node("${osName} && ${options.BUILDER_TAG}") {
                    println("Started build at ${NODE_NAME}")
                    stage("Build-${osName}") {
                        timeout(time: "${options.BUILD_TIMEOUT}", unit: 'MINUTES') {
                            ws("WS/${options.PRJ_NAME}_Build") {
                                executeBuild(osName, options)
                            }
                        }
                    }
                }
            }
            options.masterJobName = env.JOB_NAME
            options.masterBuildNumber = env.BUILD_NUMBER

            Map currentTestsBuildsIds = [:]
            String launchTestsStageName = "Launch&Wait-Tests-${osName}"
            def jobsCount = 0
            def failedJobsCount = 0
            stage(launchTestsStageName) {
                def testTasks = [:]
                // convert options Map to json to specify it as String parameter for called job
                Map newOptions = collectTestsOptions(options)
                def jsonOptions = new JsonBuilder(newOptions).toPrettyString()
                gpuNames.split(',').each() {
                    String asicName = it
                    options.testsList = options.testsList ?: ['']

                    options.testsList.each() { testName ->
                        String currentTestsName = getTestsName(asicName, osName, testName)

                        testTasks[currentTestsName] = {
                            def testBuild
                            try {
                                println("Run ${currentTestsName}")
                                testBuild = build(
                                    job: options.testsJobName,
                                    parameters: [
                                        [$class: 'StringParameterValue', name: 'PipelineBranch', value: options.pipelinesBranch],
                                        [$class: 'StringParameterValue', name: 'TestsBranch', value: options.testsBranch],
                                        [$class: 'StringParameterValue', name: 'BuildName', value: "${currentTestsName}-${options.buildId}"],
                                        [$class: 'StringParameterValue', name: 'AsicName', value: asicName],
                                        [$class: 'StringParameterValue', name: 'OsName', value: osName],
                                        [$class: 'StringParameterValue', name: 'TestName', value: testName],
                                        [$class: 'StringParameterValue', name: 'Options', value: jsonOptions]
                                    ],
                                    quietPeriod: 0
                                )
                                currentTestsBuildsIds[currentTestsName] = testBuild.number
                            } catch (e) {
                                failedJobsCount++
                                // -1 means that tests build failed and deploy build don't need to check its artifacts
                                currentTestsBuildsIds[currentTestsName] = -1
                                println "[ERROR] ${currentTestsName} finished with status 'FAILURE'"
                            } finally {
                                jobsCount++
                            }
                        }

                    }
                }

                parallel testTasks
                // write jobs which were run for this platform in common map
                testsBuildsIds << currentTestsBuildsIds

                println("[INFO] During ${launchTestsStageName} ${jobsCount} tests builds were run (${failedJobsCount} builds failed):")
                for (element in currentTestsBuildsIds) {
                    String formattedTestName = sprintf("%-50s", "${element.key}")
                    if (element.value != -1) {
                        println("[INFO]  ${formattedTestName}: run build with number #${element.value}")
                    } else {
                        println("[ERROR] ${formattedTestName}: build failed")
                    }
                }

            }
        }
        catch (e) {
            println "[ERROR] executePlatform throw the exception"
            println "Exception: ${e.toString()}"
            println "Exception message: ${e.getMessage()}"
            println "Exception cause: ${e.getCause()}"
            println "Exception stack trace: ${e.getStackTrace()}"

            currentBuild.result = "FAILURE"
            options.FAILED_STAGES.add(e.toString())
            throw e
        }
    }
    return retNode
}

def call(def platforms, def executePreBuild, def executeBuild, def executeDeploy, Map options) {

    try {

        // if it's PR - supersede all previously launched executions
        if(env.CHANGE_ID) {
            //set logRotation for PRs
            properties([[$class: 'BuildDiscarderProperty', strategy:
                [$class: 'LogRotator', artifactDaysToKeepStr: '', artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '5']]]);

            def buildNumber = env.BUILD_NUMBER as int
            if (buildNumber > 1) milestone(buildNumber - 1)
            milestone(buildNumber)

        }

        def date = new Date()
        dateFormatter = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
        options.JOB_STARTED_TIME = dateFormatter.format(date)

        /*properties([[$class: 'BuildDiscarderProperty', strategy:
                     [$class: 'LogRotator', artifactDaysToKeepStr: '',
                      artifactNumToKeepStr: '10', daysToKeepStr: '', numToKeepStr: '']]]);*/

        timestamps {
            String PRJ_PATH="${options.PRJ_ROOT}/${options.PRJ_NAME}"
            String REF_PATH="${options.PRJ_ROOT}/${options.ASSETS_NAME}/ReferenceImages"
            String JOB_PATH="${PRJ_PATH}/${JOB_NAME}/Build-${BUILD_ID}".replace('%2F', '_')
            options['PRJ_PATH']="${PRJ_PATH}"
            options['REF_PATH']="${REF_PATH}"
            options['JOB_PATH']="${JOB_PATH}"

            // if timeout doesn't set - use default
            // value in minutes
            options['PREBUILD_TIMEOUT'] = options['PREBUILD_TIMEOUT'] ?: 60
            options['BUILD_TIMEOUT'] = options['BUILD_TIMEOUT'] ?: 60
            options['TEST_TIMEOUT'] = options['TEST_TIMEOUT'] ?: 60
            options['DEPLOY_TIMEOUT'] = options['DEPLOY_TIMEOUT'] ?: 60

            options['FAILED_STAGES'] = []

            List platformList = []
            Map testsBuildsIds = [:]

            try {
                if(executePreBuild) {
                    node("Windows && PreBuild") {
                        ws("WS/${options.PRJ_NAME}_PreBuild") {
                            stage("PreBuild") {
                                try {
                                    timeout(time: "${options.PREBUILD_TIMEOUT}", unit: 'MINUTES') {
                                        executePreBuild(options)
                                        if(!options['executeBuild']) {
                                            options.CBR = 'SKIPPED'
                                            echo "Build SKIPPED"
                                        }
                                    }
                                } catch (e) {
                                    println("[ERROR] Failed during prebuild stage on ${env.NODE_NAME}")
                                    println(e.toString());
                                    println(e.getMessage());
                                    throw e
                                }
                            }
                        }
                    }
                }

                def tasks = [:]

                platforms.split(';').each() {
                    List tokens = it.tokenize(':')
                    String osName = tokens.get(0)
                    String gpuNames = ""
                    if (tokens.size() > 1) {
                        gpuNames = tokens.get(1)
                    }

                    platformList << osName

                    if(options['rebuildReport'] && gpuNames) {
                        gpuNames.split(',').each() {
                            // if not split - testsList doesn't exists
                            options.testsList = options.testsList ?: ['']
                            options['testsList'].split(' ').each() { testName ->
                                String asicName = it
                                String testsName = getTestsName(asicName, osName, testName)
                                testsBuildsIds[testsName] = -1
                            }
                        }
                    }

                    tasks[osName] = executePlatform(osName, gpuNames, executeBuild, options, testsBuildsIds)
                }

                if (options['executeTests']) {
                    parallel tasks
                }

            } catch (e) {
                println(e.toString());
                println(e.getMessage());
            } finally {
                if (options['rebuildReport']) {
                    // search ids of tests builds before rebuild report
                    buildsLeft = testsBuildsIds.size()
                    List builds = Jenkins.instance.getItem(options.testsJobName).getBuilds()
                    for (build in builds) {
                        String[] nameParts = build.getDisplayName().split('-')
                        String currentTestName = ""
                        String currentBuildId = ""
                        for (int i = 0; i < nameParts.length; i++) {
                            // parse displayName: last part - buildId, string before it - testName
                            if (i + 1 != nameParts.length) {
                                testName = testName + nameParts[i]
                            } else {
                                buildId = nameParts[i]
                            }
                        }
                        // if global id isn't equal skip this build
                        if (currentBuildId == options.buildId) {
                            continue
                        }
                        if (testsBuildsIds.containsKey(currentTestName)) {
                            // save build id if it's the first id with this name older builds will be ignored
                            if (testsBuildsIds[currentTestName] == -1) {
                                testsBuildsIds[currentTestName] = build.getNumber()
                                if (--buildsLeft == 0) {
                                    break
                                }
                            }
                        }
                    }
                    println("Found ${testsBuildsIds.size() - buildsLeft} of ${testsBuildsIds.size()} jobs. Details:")
                    for (element in testsBuildsIds) {
                        String formattedTestName = sprintf("%-50s", "${element.key}")
                        if (element.value != -1) {
                            println("[INFO]  ${formattedTestName}: found build with number #${element.value}")
                        } else {
                            println("[ERROR] ${formattedTestName}: build not found")
                        }
                    }
                }

                node("Windows && ReportBuilder") {
                    stage("Deploy") {
                        timeout(time: "${options.DEPLOY_TIMEOUT}", unit: 'MINUTES') {
                            ws("WS/${options.PRJ_NAME}_Deploy") {
                                try {
                                    if(executeDeploy && options['executeTests'] || options['rebuildReport']) {
                                        executeDeploy(options, platformList, testsBuildsIds)
                                    }
                                } catch (e) {
                                    println(e.toString());
                                    println(e.getMessage());
                                    throw e
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    catch (FlowInterruptedException e) {
        println(e.toString());
        println(e.getMessage());
        echo "Job was ABORTED by user. Job status: ${currentBuild.result}"
    }
    catch (e) {
        println(e.toString());
        println(e.getMessage());
        currentBuild.result = "FAILURE"
        throw e
    }
    finally {
        println "[INFO] BUILD RESULT: ${currentBuild.result}"
        println "[INFO] BUILD CURRENT RESULT: ${currentBuild.currentResult}"
    }
}
