def executeTestCommand(String osName, String libType, Boolean testPerformance)
{
    switch(osName) {
        case 'Windows':
            dir("unittest") {
                bat "mkdir testSave"
                if (testPerformance) {
                    bat """
                    set RIF_AI_FP16_ENABLED=1
                    ..\\bin\\UnitTest.exe --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ..\\${STAGE_NAME}.${libType}.log  2>&1
                    """
                } else {
                    bat """
                    set RIF_AI_FP16_ENABLED=1
                    ..\\bin\\UnitTest.exe -t .\\testSave -r .\\referenceImages --models ..\\models --gtest_filter=\"*.*/0\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ..\\${STAGE_NAME}.${libType}.log  2>&1
                    """
                }
            }
            break
        case 'OSX':
            dir("unittest") {
                sh "mkdir testSave"
                if (testPerformance) {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                } else {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest  -t ./testSave -r ./referenceImages --models ../models --gtest_filter=\"*.*/0\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                }
            }
            break
        default:
            dir("unittest") {
                sh "mkdir testSave"
                if (testPerformance) {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest --mode p --gtest_filter=\"Performance.*\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                } else {
                    sh "RIF_AI_FP16_ENABLED=1 ../bin/UnitTest  -t ./testSave -r ./referenceImages --models ../models --gtest_filter=\"*.*/0\" --gtest_output=xml:../${STAGE_NAME}.${libType}.gtest.xml >> ../${STAGE_NAME}.${libType}.log  2>&1"
                }
            }
    }
}


def executeTestsForCustomLib(String osName, String libType, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName, "${STAGE_NAME}.${libType}")
        unstash "app_${libType}_${osName}"
        executeTestCommand(osName, libType, options.testPerformance)
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        archiveArtifacts "*.log"
        if (options.testPerformance) {
            switch(osName) {
                case 'Windows':
                    bat """
                        move unittest\\rif_performance_*.csv .
                        rename rif_performance_*.csv ${STAGE_NAME}.${libType}.csv
                    """
                    break
                case 'OSX':
                    sh """
                        mv unittest/rif_performance_*.csv ./${STAGE_NAME}.${libType}.csv
                    """
                    break
                default:
                    sh """
                        mv unittest/rif_performance_*.csv ./${STAGE_NAME}.${libType}.csv
                    """
                    break
            }
            stash includes: "${STAGE_NAME}.${libType}.gtest.xml, ${STAGE_NAME}.${libType}.csv", name: "${options.testResultsName}.${libType}", allowEmpty: true
        }
        junit "*.gtest.xml"
    }
}


def executeTests(String osName, String asicName, Map options)
{
    Boolean testsFailed = false

    try {
        executeTestsForCustomLib(osName, 'dynamic', options)
    } catch (e) {
        println("Error during testing dynamic lib")
        testsFailed = true
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace()) 
    }

    try {
        executeTestsForCustomLib(osName, 'static', options)
    } catch (e) {
        testsFailed = true
        println("Error during testing static lib")
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace())   
    }

    if (testsFailed) {
        currentBuild.result = "FAILED"
        error "Error during testing"
    }

}


def executeBuildWindows(String cmakeKeys, String osName, Map options)
{
    bat """
        set msbuild="C:\\Program Files (x86)\\Microsoft Visual Studio\\2017\\Community\\MSBuild\\15.0\\Bin\\MSBuild.exe" >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        mkdir build-${options.packageName}-${osName}-dynamic
        cd build-${options.packageName}-${osName}-dynamic
        cmake .. -DADL_PROFILING=ON -G "Visual Studio 15 2017 Win64" -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-dynamic >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        %msbuild% INSTALL.vcxproj -property:Configuration=Release >> ..\\${STAGE_NAME}.dynamic.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static
        cd build-${options.packageName}-${osName}-static
        cmake .. -DADL_PROFILING=ON -G "Visual Studio 15 2017 Win64" -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static -DRIF_STATIC_LIB=ON >> ..\\${STAGE_NAME}.static.log 2>&1
        %msbuild% INSTALL.vcxproj -property:Configuration=Release >> ..\\${STAGE_NAME}.static.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static-runtime
        cd build-${options.packageName}-${osName}-static-runtime
        cmake .. -DADL_PROFILING=ON -G "Visual Studio 15 2017 Win64" -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static-runtime -DRIF_STATIC_RUNTIME_LIB=ON >> ..\\${STAGE_NAME}.static-runtime.log 2>&1
        %msbuild% INSTALL.vcxproj -property:Configuration=Release >> ..\\${STAGE_NAME}.static-runtime.log 2>&1
        cd ..
    """

    // Stash for testing only
    dir("${options.packageName}-${osName}-dynamic") {
        stash includes: "bin/*", name: "app_dynamic_${osName}"
    }

    dir("${options.packageName}-${osName}-static") {
        stash includes: "bin/*", name: "app_static_${osName}"
    }

    bat """
        xcopy README.md ${options.packageName}-${osName}-dynamic\\README.md* /y
        xcopy README.md ${options.packageName}-${osName}-static\\README.md* /y
        xcopy README.md ${options.packageName}-${osName}-static-runtime\\README.md* /y

        cd ${options.packageName}-${osName}-dynamic
        del /S UnitTest*
        cd ..

        cd ${options.packageName}-${osName}-static
        del /S UnitTest*
        cd ..

        cd ${options.packageName}-${osName}-static-runtime
        del /S UnitTest*
        cd ..
    """

    // Stash for github repo
    dir("${options.packageName}-${osName}-dynamic/bin") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-dynamic-${osName}"
    }

    dir("${options.packageName}-${osName}-static/bin") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-${osName}"
    }

    dir("${options.packageName}-${osName}-static-runtime/bin") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-runtime-${osName}"
    }

    stash includes: "models/**/*", name: "models"
    stash includes: "samples/**/*", name: "samples"
    stash includes: "include/**/*", name: "include"

    dir ('src') {
        stash includes: "License.txt", name: "txtFiles"
    }

    bat """
        mkdir RIF_Release
        mkdir RIF_Debug
        mkdir RIF_Samples
        mkdir RIF_Models

        xcopy ${options.packageName}-${osName}-dynamic RIF_Dynamic\\${options.packageName}-${osName}-dynamic /s/y/i
        xcopy ${options.packageName}-${osName}-static RIF_Static\\${options.packageName}-${osName}-static /s/y/i
        xcopy ${options.packageName}-${osName}-static-runtime RIF_Static_Runtime\\${options.packageName}-${osName}-static-runtime /s/y/i
        xcopy samples RIF_Samples\\samples /s/y/i
        xcopy models RIF_Models\\models /s/y/i
    """

    zip archive: true, dir: 'RIF_Dynamic', glob: '', zipFile: "${options.packageName}-${osName}-dynamic.zip"
    zip archive: true, dir: 'RIF_Static', glob: '', zipFile: "${options.packageName}-${osName}-static.zip"
    zip archive: true, dir: 'RIF_Static_Runtime', glob: '', zipFile: "${options.packageName}-${osName}-static-runtime.zip"
    zip archive: true, dir: 'RIF_Samples', glob: '', zipFile: "${options.samplesName}.zip"
    zip archive: true, dir: 'RIF_Models', glob: '', zipFile: "${options.modelsName}.zip"

    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>${osName}: <a href="${BUILD_URL}artifact/${options.packageName}-${osName}-dynamic.zip">dynamic</a> / <a href="${BUILD_URL}/artifact/${options.packageName}-${osName}-static.zip">static</a> / <a href="${BUILD_URL}/artifact/${options.packageName}-${osName}-static-runtime.zip">static-runtime</a> </h4>"""
    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>Samples: <a href="${BUILD_URL}artifact/${options.samplesName}.zip">${options.samplesName}.zip</a></h4>"""
    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>Models: <a href="${BUILD_URL}artifact/${options.modelsName}.zip">${options.modelsName}.zip</a></h4>"""
}

def executeBuildUnix(String cmakeKeys, String osName, Map options, String compilerName="gcc")
{
    String EXPORT_CXX = compilerName == "clang-5.0" ? "export CXX=clang-5.0" : ""
    String SRC_BUILD = compilerName == "clang-5.0" ? "RadeonImageFilters" : "all"

    sh """
        ${EXPORT_CXX}
        mkdir build-${options.packageName}-${osName}-dynamic
        cd build-${options.packageName}-${osName}-dynamic
        cmake .. -DADL_PROFILING=ON ${cmakeKeys} -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-dynamic >> ../${STAGE_NAME}.dynamic.log 2>&1
        make -j 8 ${SRC_BUILD} >> ../${STAGE_NAME}.dynamic.log 2>&1
        make install >> ../${STAGE_NAME}.dynamic.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static
        cd build-${options.packageName}-${osName}-static
        cmake .. -DADL_PROFILING=ON ${cmakeKeys} -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static -DRIF_STATIC_LIB=ON >> ../${STAGE_NAME}.static.log 2>&1
        make -j 8 ${SRC_BUILD} >> ../${STAGE_NAME}.static.log 2>&1
        make install >> ../${STAGE_NAME}.static.log 2>&1
        cd ..

        mkdir build-${options.packageName}-${osName}-static-runtime
        cd build-${options.packageName}-${osName}-static-runtime
        cmake .. -DADL_PROFILING=ON ${cmakeKeys} -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=../${options.packageName}-${osName}-static-runtime -DRIF_STATIC_RUNTIME_LIB=ON >> ../${STAGE_NAME}.static-runtime.log 2>&1
        make -j 8 ${SRC_BUILD} >> ../${STAGE_NAME}.static-runtime.log 2>&1
        make install >> ../${STAGE_NAME}.static-runtime.log 2>&1
        cd ..
    """

    // Stash for testing
    dir("${options.packageName}-${osName}-dynamic") {
        stash includes: "bin/*", name: "app_dynamic_${osName}"
    }

    dir("${options.packageName}-${osName}-static") {
        stash includes: "bin/*", name: "app_static_${osName}"
    }

    sh """
        cp README.md ${options.packageName}-${osName}-dynamic
        cp README.md ${options.packageName}-${osName}-static
        cp README.md ${options.packageName}-${osName}-static-runtime
    """

    if (compilerName != "clang-5.0") {
        sh """
            rm ${options.packageName}-${osName}-dynamic/bin/UnitTest*
            rm ${options.packageName}-${osName}-static/bin/UnitTest*
            rm ${options.packageName}-${osName}-static-runtime/bin/UnitTest*
        """
    }

    sh """
        tar cf ${options.packageName}-${osName}-dynamic.tar ${options.packageName}-${osName}-dynamic
        tar cf ${options.packageName}-${osName}-static.tar ${options.packageName}-${osName}-static
        tar cf ${options.packageName}-${osName}-static-runtime.tar ${options.packageName}-${osName}-static-runtime
    """

    archiveArtifacts "${options.packageName}-${osName}*.tar"

    dir("${options.packageName}-${osName}-dynamic/bin/") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-dynamic-${osName}"
    }

    dir("${options.packageName}-${osName}-static/bin/") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-${osName}"
    }

    dir("${options.packageName}-${osName}-static-runtime/bin/") {
        stash includes: "*", excludes: '*.exp, *.pdb', name: "deploy-static-runtime-${osName}"
    }

    rtp nullAction: '1', parserName: 'HTML', stableText: """<h4>${osName}: <a href="${BUILD_URL}artifact/${options.packageName}-${osName}-dynamic.tar">dynamic</a> / <a href="${BUILD_URL}/artifact/${options.packageName}-${osName}-static.tar">static</a> / <a href="${BUILD_URL}/artifact/${options.packageName}-${osName}-static-runtime.tar">static-runtime</a> </h4>"""
}


def getArtifactName(String name, String branch, String commit) {
    String return_name = name + (branch ? '-' + branch : '') + (commit ? '-' + commit : '')
    return return_name.replaceAll('[^a-zA-Z0-9-_.]+','')
}


def executePreBuild(Map options)
{
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    options.commitShortSHA = options.commitSHA[0..6]
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    String branch = env.BRANCH_NAME ? env.BRANCH_NAME : env.Branch
    options.branch = branch.replace('origin/', '')

    options.packageName = getArtifactName('radeonimagefilters', options.branch, options.commitShortSHA)
    options.modelsName = getArtifactName('models', options.branch, options.commitShortSHA)
    options.samplesName = getArtifactName('samples', options.branch, options.commitShortSHA)
}


def executeBuild(String osName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName, "${STAGE_NAME}.dynamic")
        outputEnvironmentInfo(osName, "${STAGE_NAME}.static")
        outputEnvironmentInfo(osName, "${STAGE_NAME}.static-runtime")

        switch(osName) {
            case 'Windows':
                executeBuildWindows(options.cmakeKeys, osName, options)
                break
            case 'OSX':
                executeBuildUnix(options.cmakeKeys, osName, options, 'clang')
                break
            case 'Ubuntu18-Clang':
                executeBuildUnix("${options.cmakeKeys} -DRIF_UNITTEST=OFF -DCMAKE_CXX_FLAGS=\"-D_GLIBCXX_USE_CXX11_ABI=0\"", osName, options, 'clang-5.0')
                break
            default:
                executeBuildUnix(options.cmakeKeys, osName, options)
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executeDeploy(Map options, List platformList, List testResultList)
{
    cleanWS()

    if (options.testPerformance) {
        dir("testResults") {
            testResultList.each() {
                try {
                    unstash "${it}.dynamic"
                    unstash "${it}.static"
                } catch(e) {
                    echo "[ERROR] Failed to unstash ${it}"
                    println(e.toString());
                    println(e.getMessage());
                }

            }
        }

        dir("rif-report") {
            checkoutScm(branchName: "master", repositoryUrl: "git@github.com:luxteam/rif_report.git")

            bat """
                set PATH=c:\\python39\\;c:\\python39\\scripts\\;%PATH%
                pip install --user -r requirements.txt >> ${STAGE_NAME}.requirements.log 2>&1
                python build_report.py --test_results ..\\testResults --output_dir ..\\results
            """
        }

        utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html", "Test Report", "Summary Report")

    } else {
        checkoutScm(branchName: "master", repositoryUrl: "git@github.com:Radeon-Pro/RadeonProImageProcessingSDK.git")

        bat """
            git rm -r *
        """

        platformList.each() {
            dir(it) {
                dir("Dynamic"){
                    unstash "deploy-dynamic-${it}"
                }
                dir("Static"){
                    unstash "deploy-static-${it}"
                }
                dir("Static-Runtime"){
                    unstash "deploy-static-runtime-${it}"
                }
            }
        }

        unstash "models"
        unstash "samples"
        unstash "txtFiles"
        unstash "include"

        bat """
            git add --all
            git commit -m "buildmaster: SDK release v${env.TAG_NAME}"
            git tag -a rif_sdk_${env.TAG_NAME} -m "rif_sdk_${env.TAG_NAME}"
            git push --tag origin HEAD:master
        """
    }
}

def call(String projectBranch = "",
         String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,AMD_WX7100,NVIDIA_GF1080TI,NVIDIA_RTX2080TI,AMD_RadeonVII,AMD_RX5700XT,AMD_RX6800;Ubuntu18:NVIDIA_RTX2070,AMD_RadeonVII;Ubuntu20:AMD_RadeonVII;OSX:AMD_RXVEGA,AMD_RX5700XT;CentOS7;Ubuntu18-Clang',
         Boolean updateRefs = false,
         Boolean enableNotifications = true,
         String cmakeKeys = '',
         Boolean testPerformance = false,
         String tester_tag = 'RIF') {

    println "TAG_NAME: ${env.TAG_NAME}"

    def deployStage = env.TAG_NAME || testPerformance ? this.&executeDeploy : null
    platforms = env.TAG_NAME ? "Windows;Ubuntu18;Ubuntu18-Clang;Ubuntu20;OSX;CentOS7;" : platforms

    def nodeRetry = []

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, deployStage,
                           [projectBranch:projectBranch,
                            projectRepo:'git@github.com:Radeon-Pro/RadeonProImageProcessing.git',
                            enableNotifications:enableNotifications,
                            TESTER_TAG:tester_tag,
                            BUILD_TIMEOUT:'40',
                            TEST_TIMEOUT:'45',
                            executeBuild:true,
                            executeTests:true,
                            PRJ_NAME:"RadeonProImageProcessor",
                            PRJ_ROOT:"rpr-core",
                            cmakeKeys:cmakeKeys,
                            testPerformance:testPerformance,
                            nodeRetry: nodeRetry,
                            retriesForTestStage:1])
}
