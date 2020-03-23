def call(String osName, Map options, String tool, String logs, boolean clear=true, String matlib='')
{
    switch(osName)
    {
        case 'Windows':
            if (checkExistenceOfPlugin(options.pluginWinSha, tool, logs, clear)) {
                  println '[INFO] Current plugin is already installed.'
                  return false
            } else {
                println '[INFO] Uninstalling plugin'
                uninstallWin(tool, logs)
                println '[INFO] Installing plugin'
                installWin(options.pluginWinSha, tool, logs)
                if (matlib){
                    echo '[INFO] Reinstalling Material Library'
                    uninstallMSI("Radeon%Material%", logs)
                    installMSI(matlib, logs)
                }
                return true
            }

        case 'OSX':
            // TODO: make implicit plugin deletion
            // TODO: implement matlib install
            println '[INFO] Installing plugin'
            installOSX(options.pluginOSXSha, tool, logs, clear)
            return true

        default:
            // TODO: make implicit plugin deletion
            println '[INFO] Installing plugin'
            installLinux(options.pluginUbuntuSha, tool, logs, clear, osName, '2.82')
            if (matlib){
                println '[INFO] Reinstalling Material Library'
                installMatLibLinux(matlib, logs)
            }
            return true
    }
}


def uninstallWin(String tool, String logs)
{
    
    // Remove RadeonProRender Addon from Blender 2.82
    // FIXME: blender version hardcode
    if(tool == 'Blender'){
        try
        {
            bat """
                echo "Disabling RPR Addon for Blender." >> ${logs}.uninstall.log 2>&1

                echo import bpy >> disableRPRAddon.py
                echo bpy.ops.preferences.addon_disable(module="rprblender")  >> disableRPRAddon.py
                echo bpy.ops.wm.save_userpref() >> disableRPRAddon.py
                "C:\\Program Files\\Blender Foundation\\Blender 2.82\\blender.exe" -b -P disableRPRAddon.py >> ${logs}.uninstall.log 2>&1

                echo "Removing RPR Addon for Blender." >> ${logs}.uninstall.log 2>&1

                echo import bpy >> removeRPRAddon.py
                echo bpy.ops.preferences.addon_remove(module="rprblender") >> removeRPRAddon.py
                echo bpy.ops.wm.save_userpref() >> removeRPRAddon.py

                "C:\\Program Files\\Blender Foundation\\Blender 2.82\\blender.exe" -b -P removeRPRAddon.py >> ${logs}.uninstall.log 2>&1
            """
        }
        catch(e)
        {
            echo "[ERROR] Failed to delete RPR Addon from Blender"
            println(e.toString())
            println(e.getMessage())
        }
    }
    
    uninstallMSI("Radeon%${tool}%", logs)
}
    

def checkExistenceOfPlugin(String pluginSha, String tool, String logs, boolean clear) 
{
    if (clear) {
        clearBinariesWin()
        checkExistWin(pluginSha, tool, logs)
    }

    println "[INFO] Checking existence of the RPR plugin on test PC."
    println "[INFO] MSI name: ${pluginSha}.msi"
    
    // Finding installed plugin on test PC
    String installedProductCode =  powershell(
            script: """
            (Get-WmiObject -Class Win32_Product -Filter \"Name LIKE 'Radeon%${tool}%'\").IdentifyingNumber
            """, returnStdout: true).trim()

    println "[INFO] Installed MSI product code: ${installedProductCode}"

    // Reading built msi file
    bat """

        echo import msilib >> getMsiProductCode.py
        echo db = msilib.OpenDatabase(r'${pluginSha}.msi', msilib.MSIDBOPEN_READONLY) >> getMsiProductCode.py
        echo view = db.OpenView("SELECT Value FROM Property WHERE Property='ProductCode'") >> getMsiProductCode.py
        echo view.Execute(None) >> getMsiProductCode.py
        echo print(view.Fetch().GetString(1)) >> getMsiProductCode.py

    """

    String msiProductCode = python3("getMsiProductCode.py").split('\r\n')[2].trim()

    println "[INFO] Built MSI product code: ${msiProductCode}"

    return installedProductCode==msiProductCode
}


def installWin(String pluginSha, String tool, String logs)
{
    
    installMSI("${pluginSha}.msi")

    // Installing RPR Addon in Blender
    // FIXME: blender version hardcode
    if (tool == 'Blender') {
        try
        {
            bat"""
                echo "Installing RPR Addon in Blender" >> ${logs}.install.log
            """

            bat """
                echo import bpy >> registerRPRinBlender.py
                echo addon_path = "C:\\Program Files\\AMD\\RadeonProRenderPlugins\\Blender\\\\addon.zip" >> registerRPRinBlender.py
                echo bpy.ops.preferences.addon_install(filepath=addon_path) >> registerRPRinBlender.py
                echo bpy.ops.preferences.addon_enable(module="rprblender") >> registerRPRinBlender.py
                echo bpy.ops.wm.save_userpref() >> registerRPRinBlender.py

                "C:\\Program Files\\Blender Foundation\\Blender 2.82\\blender.exe" -b -P registerRPRinBlender.py >> ${logs}.install.log 2>&1
            """
        }
        catch(e)
        {
            println "[ERROR] Failed to install RPR Addon in Blender"
            println(e.toString())
            println(e.getMessage())
        }
    }
}


def checkExistWin(String pluginSha, String tool, String logs){
    if(!(fileExists("${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.msi")))
    {
        println "[INFO] The plugin does not exist in the storage."
        bat """
            IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
            rename RadeonProRender${tool}.msi ${pluginSha}.msi
            copy ${pluginSha}.msi "${CIS_TOOLS}\\..\\PluginsBinaries\\${pluginSha}.msi"
        """
    }
    else
    {
        println "[INFO] The plugin exists in the storage."
        bat """
            copy "${CIS_TOOLS}\\..\\PluginsBinaries\\${pluginSha}.msi" ${pluginSha}.msi
        """
    }
}


def installMatLibLinux(String msiName, String logs)
{
    receiveFiles("bin_storage/RadeonProRenderMaterialLibraryInstaller_2.0.run", "${CIS_TOOLS}/../TestResources/")

    sh """
        #!/bin/bash
        ${CIS_TOOLS}/../TestResources/RadeonProRenderMaterialLibraryInstaller_2.0.run --nox11 -- --just-do-it >> ${logs}.matlib.install.log 2>&1
    """
}


def installOSX(String pluginSha, String tool, String logs, boolean clear)
{
    if (clear){
        clearBinariesUnix()
        checkExistOSX(pluginSha, tool)
    }

    sh"""
        $CIS_TOOLS/install${tool}Plugin.sh ${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.dmg >> ${logs}.install.log 2>&1
    """
}


def checkExistOSX(String pluginSha, String tool){
    if(!(fileExists("${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.dmg")))
    {
        sh """
            mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
            mv RadeonProRender${tool}.dmg "${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.dmg"
        """
    }
}


def installLinux(String pluginSha, String tool, String logs, boolean clear, String osName, String blenderVersion)
{
    // remove installed plugin
    try
    {
        sh"""
            /home/user/.local/share/rprblender/uninstall.py /home/user/Desktop/Blender${blenderVersion}/ >> ${logs}.uninstall.log 2>&1
        """
    }

    catch(e)
    {
        echo "[ERROR] Failed to deinstall plugin"
        println(e.toString())
        println(e.getMessage())
    }

    if (clear){
        clearBinariesUnix()
        checkExistUnix(pluginSha, osName)
    }

    // install plugin
    sh """
        #!/bin/bash
        printf "y\nq\n\ny\ny\n" > input.txt
        exec 0<input.txt
        exec &>${logs}.install.log
        ${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.run --nox11 --noprogress ~/Desktop/Blender${blenderVersion} >> ${logs}.install.log 2>&1
    """
}


def checkExistUnix(String pluginSha, String osName)
{
    if(!(fileExists("${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.run")))
    {
        sh """
            mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
            chmod +x RadeonProRenderBlender.run
            mv RadeonProRenderBlender.run "${CIS_TOOLS}/../PluginsBinaries/${pluginSha}.run"
        """
    }
}
