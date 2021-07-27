import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Implementation of archive artifacts through custom machine
 *
 * @param artifactName name of artifact
 */
def call(String artifactName) {
    try {
        String path = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/"
        makeStash(includes: artifactName, name: artifactName, allowEmpty: true, customLocation: path, preZip: false, postUnzip: false, storeOnNAS: true)

        String artifactURL

        withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
            string(credentialsId: "nasURLFrontend", variable: "REMOTE_URL")]) {
            artifactURL = "${REMOTE_URL}/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/${artifactName}"
        }

        String authArtifactURL

        withCredentials([usernamePassword(credentialsId: "reportsNAS", usernameVariable: "NAS_USER", passwordVariable: "NAS_PASSWORD")]) {
            authArtifactURL = artifactURL.replace("https://", "https://${NAS_USER}:${NAS_PASSWORD}@")

            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${authArtifactURL}">[BUILD: ${BUILD_ID}] ${artifactName}</a></h3>"""
        }

        return authArtifactURL
    } catch (e) {
        println("[ERROR] Failed to archive artifacts")
        println(e.toString())
        println(e.getMessage())
        println(e.getStackTrace())
        throw e
    }

}
