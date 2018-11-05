def executeGenTestRefCommand(String osName, Map options)
{
    executeTestCommand(osName, options)
    
    try
    {   
        //for update existing manifest file
        receiveFiles("${options.REF_PATH_PROFILE}/baseline_manifest.json", './Work/Baseline/')
    }
    catch(e)
    {
        println("baseline_manifest.json not found")
    }
    
    dir('scripts')
    {
        switch(osName)
        {
            case 'Windows':
                bat """
                make_results_baseline.bat
                """
                break;
            case 'OSX':
                sh """
                echo 'sample image' > ./ReferenceImages/sample_image.txt
                """
                break;
            default:
                sh """
                ./make_results_baseline.sh
                """
        }
    }
}

def executeTestCommand(String osName, Map options)
{
    switch(osName)
    {
    case 'Windows':
        
        dir('temp')
        {
            unstash 'WindowsSDK'
            try
            {
                bat "xcopy binWin64 c:\\rprSdkWin64 /s/y"
            }
            catch(e)
            {
                currentBuild.result = "FAILED"
                throw e
            }
        }
        
        dir('scripts')
        {
            bat """
            run.bat ${options.testsPackage} \"${options.tests}\" ${options.width} ${options.height} ${options.iterations} >> ../${STAGE_NAME}.log  2>&1
            """
        }
        break;
    case 'OSX':
        sh """
        echo 'sample image' > ./OutputImages/sample_image.txt
        """
        break;
    default:
        sh """
        echo 'sample image' > ./OutputImages/sample_image.txt
        """
    }
}

def executeTests(String osName, String asicName, Map options)
{
    try {

        checkoutGit(options['testsBranch'], 'git@github.com:luxteam/jobs_test_core.git')
        
        String REF_PATH_PROFILE="${options.REF_PATH}/${asicName}-${osName}"
        String JOB_PATH_PROFILE="${options.JOB_PATH}/${asicName}-${osName}"
        
        options.REF_PATH_PROFILE = REF_PATH_PROFILE
        
        outputEnvironmentInfo(osName)
        
        if(options['updateRefs'])
        {
            executeGenTestRefCommand(osName, options)
            sendFiles('./Work/Baseline/', REF_PATH_PROFILE)
        }
        else if(options.updateRefsByOne)
        {
            executeGenTestRefCommand(osName, options)
            ['AMD_RXVEGA', 'AMD_WX9100', 'AMD_WX7100'].each
            {
                sendFiles('./Work/Baseline/', "${options.REF_PATH}/${it}-Windows")
            }
        }
        else
        {
            receiveFiles("${REF_PATH_PROFILE}/*", './Work/Baseline/')
            executeTestCommand(osName, options)    
        }
    }
    catch (e) {
        println(e.toString());
        println(e.getMessage());
        currentBuild.result = "FAILED"
        throw e
    }
    finally {
        archiveArtifacts "*.log"
        echo "Stashing test results to : ${options.testResultsName}"
        dir('Work')
        {
            stash includes: '**/*', name: "${options.testResultsName}", allowEmpty: true
        }
    }
}

def executeBuildWindows(Map options)
{
    dir('RadeonProRenderSDK/RadeonProRender')
    {
        stash includes: 'binWin64/*', name: 'WindowsSDK'
    }
}

def executeBuildOSX(Map options)
{
 
}

def executeBuildLinux(Map options)
{
    
}

def executeBuild(String osName, Map options)
{
    try {        
        dir('RadeonProRenderSDK')
        {
            checkoutGit(options['projectBranch'], 'https://github.com/Radeon-Pro/RadeonProRenderSDK.git')
        }
        
        outputEnvironmentInfo(osName)

        switch(osName)
        {
        case 'Windows': 
            executeBuildWindows(options); 
            break;
        case 'OSX':
            executeBuildOSX(options);
            break;
        default: 
            executeBuildLinux(options);
        }
    }
    catch (e) {
        currentBuild.result = "FAILED"
        throw e
    }
    finally {
        archiveArtifacts "*.log"
    }                        
}

def executePreBuild(Map options)
{
    currentBuild.description = ""
    ['projectBranch'].each
    {
        if(options[it] != 'master' && options[it] != "")
        {
            currentBuild.description += "<b>${it}:</b> ${options[it]}<br/>"
        }
    }
    
    checkoutGit(options['projectBranch'], 'https://github.com/Radeon-Pro/RadeonProRenderSDK.git')

    AUTHOR_NAME = bat (
            script: "git show -s --format=%%an HEAD ",
            returnStdout: true
            ).split('\r\n')[2].trim()

    echo "The last commit was written by ${AUTHOR_NAME}."
    options.AUTHOR_NAME = AUTHOR_NAME

    commitMessage = bat ( script: "git log --format=%%B -n 1", returnStdout: true )
    echo "Commit message: ${commitMessage}"
    options.commitMessage = commitMessage.split('\r\n')[2].trim()

    options['commitSHA'] = bat(script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
       
    if(!env.CHANGE_URL)
    {
        currentBuild.description += "<b>Commit author:</b> ${options.AUTHOR_NAME}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    }
    
    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        properties([[$class: 'BuildDiscarderProperty', strategy: 	
                         [$class: 'LogRotator', artifactDaysToKeepStr: '', 	
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    } else if (env.BRANCH_NAME && BRANCH_NAME != "master") {
        properties([[$class: 'BuildDiscarderProperty', strategy: 	
                         [$class: 'LogRotator', artifactDaysToKeepStr: '', 	
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '3']]]);
    } else {
        properties([[$class: 'BuildDiscarderProperty', strategy: 	
                         [$class: 'LogRotator', artifactDaysToKeepStr: '', 	
                          artifactNumToKeepStr: '', daysToKeepStr: '', numToKeepStr: '10']]]);
    }
}

def executeDeploy(Map options, List platformList, List testResultList)
{
    try { 
        if(options['executeTests'] && testResultList)
        {
            checkoutGit(options['testsBranch'], 'https://github.com/luxteam/jobs_test_core.git')

            dir("summaryTestResults")
            {
                testResultList.each()
                {
                    dir("$it".replace("testResult-", ""))
                    {
                        try
                        {
                            unstash "$it"
                        }catch(e)
                        {
                            echo "Can't unstash ${it}"
                            println(e.toString());
                            println(e.getMessage());
                        }
                    
                    }
                }
            }

            dir("jobs_launcher")
            {
                if(options.projectBranch != "") {
                    options.branchName = options.projectBranch
                } else {
                    options.branchName = env.BRANCH_NAME
                }
                if(options.incrementVersion) {
                    options.branchName = "master"
                }
                
                options.commitMessage = options.commitMessage.replace("'", "")
                options.commitMessage = options.commitMessage.replace('"', '')
                bat """
                build_reports.bat ..\\summaryTestResults Core ${options.commitSHA} ${options.branchName} \\"${options.commitMessage}\\"
                """
                bat "get_status.bat ..\\summaryTestResults"
            }
            
            try
            {
                def summaryReport = readJSON file: 'summaryTestResults/summary_status.json'
                if (summaryReport.failed > 0 || summaryReport.error > 0)
                {
                    println("Some tests failed")
                    currentBuild.result="UNSTABLE"
                }
            }
            catch(e)
            {
                println("CAN'T GET TESTS STATUS")
            }
            
            try
            {
                options.testsStatus = readFile("summaryTestResults/slack_status.json")
            }
            catch(e)
            {
                println(e.toString())
                println(e.getMessage())
                options.testsStatus = ""
            }   

            publishHTML([allowMissing: false, 
                         alwaysLinkToLastBuild: false, 
                         keepAll: true, 
                         reportDir: 'summaryTestResults', 
                         reportFiles: 'summary_report.html, performance_report.html, compare_report.html',
                         reportName: 'Test Report',
                         reportTitles: 'Summary Report, Performance Report, Compare Report'])
        }
    }
    catch (e) {
        currentBuild.result = "FAILED"
        println(e.toString());
        println(e.getMessage());
        throw e
    }
    finally
    {}   
}


def call(String projectBranch = "",
         String testsBranch = "master",
         String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,AMD_WX7100,NVIDIA_GF1080TI', 
         Boolean updateRefs = false,
         Boolean updateRefsByOne = false,
         Boolean enableNotifications = true,
         Boolean skipBuild = false,
         String renderDevice = "gpu",
         String testsPackage = "Full",
         String tests = "",
         String width = "0",
         String height = "0",
         String iterations = "0") {
    try
    {
        String PRJ_NAME="RadeonProRenderCore"
        String PRJ_ROOT="rpr-core"

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, 
                               [projectBranch:projectBranch, 
                                testsBranch:testsBranch, 
                                updateRefs:updateRefs,
                                updateRefsByOne:updateRefsByOne,
                                enableNotifications:enableNotifications,
                                PRJ_NAME:PRJ_NAME,
                                PRJ_ROOT:PRJ_ROOT,
                                BUILDER_TAG:'BuilderS',
                                skipBuild:skipBuild,
                                renderDevice:renderDevice,
                                testsPackage:testsPackage,
                                tests:tests.replace(',', ' '),
                                executeBuild:true,
                                executeTests:true,
                                reportName:'Test_20Report',
                                width:width,
                                height:height,
                                iterations:iterations])
    }
    catch(e) {
        currentBuild.result = "FAILED"
        println(e.toString());
        println(e.getMessage());
        throw e
    }
}