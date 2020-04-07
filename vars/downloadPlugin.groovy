def call(String osName, String tool, Map options)
{
    String customBuildLink = ""
    String extentsion = ""

    switch(osName)
    {
        case 'Windows':
            customBuildLink = options['customBuildLinkWindows']
            extentsion = "msi"
            break;
        case 'OSX':
            customBuildLink = options['customBuildLinkOSX']
            extentsion = "dmg"
            break;
        default:
            customBuildLink = options['customBuildLinkLinux']
            extentsion = "run"
    }

    if (tool == "Blender") {
        extentsion = "zip"
    }

    print "[INFO] Used specified pre built plugin for ${tool}."

    if (customBuildLink.startsWith("https://builds.rpr")) 
    {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'builsRPRCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            if (osName == "Windows") {
                bat """
                    curl -L -o RadeonProRender${tool}_${osName}.${extentsion} -u %USERNAME%:%PASSWORD% "${customBuildLink}"
                """
            } else {
                sh """
                    curl -L -o RadeonProRender${tool}_${osName}.${extentsion} -u %USERNAME%:%PASSWORD% '"${customBuildLink}"'
                """
            }
        }
    }
    else
    {
        if (osName == "Windows") {
            bat """
                curl -L -o RadeonProRender${tool}_${osName}.${extentsion} "${customBuildLink}"
            """
        } else {
            sh """
                curl -L -o RadeonProRender${tool}_${osName}.${extentsion} '"${customBuildLink}"'
            """
        }
    }

    // We haven't any branch so we use sha1 for idetifying plugin build
    def pluginSha = sha1 "RadeonProRender${tool}_${osName}.${extentsion}"

    switch(osName)
    {
        case 'Windows':
            options.pluginWinSha = pluginSha
            break;
        case 'OSX':
            options.pluginOSXSha = pluginSha
            break;
        default:
            options.pluginUbuntuSha = pluginSha
    }
}