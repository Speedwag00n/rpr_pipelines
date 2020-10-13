import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import hudson.model.Result
import groovy.json.JsonOutput

class utils {

    static int getTimeoutFromXML(Object self, String test, String keyword, Integer additional_xml_timeout) 
    {
        try {
            String xml = self.readFile("jobs/Tests/${test}/test.job-manifest.xml")
            for (xml_string in xml.split("<")) {
                if (xml_string.contains("${keyword}")) {
                    Integer xml_timeout = (Math.round((xml_string.findAll(/\d+/)[0] as Double).div(60)) + additional_xml_timeout)
                    return xml_timeout
                }
            }
        } catch (e) {
            self.println(e)
            return -1
        }
        
    }

    static def setForciblyBuildResult(RunWrapper currentBuild, String buildResult) {
        currentBuild.build().@result = Result.fromString(buildResult)
    }

    static def isTimeoutExceeded(Exception e) {
        Boolean result = false
        String exceptionClassName = e.getClass().toString()
        if (exceptionClassName.contains("FlowInterruptedException")) {
            //sometimes FlowInterruptedException generated by 'timeout' block doesn't contain exception cause
            if (!e.getCause()) {
                result = true
            } else {
                for (cause in e.getCauses()) {
                    String causeClassName = cause.getClass().toString()
                    if (causeClassName.contains("ExceededTimeout") || causeClassName.contains("TimeoutStepExecution")) {
                        result = true
                        break
                    }
                }
            }
        }
        return result
    }

    static def markNodeOffline(Object self, String nodeName, String offlineMessage)
    {
        try {
            def nodes = jenkins.model.Jenkins.instance.getLabel(nodeName).getNodes()
            nodes[0].getComputer().doToggleOffline(offlineMessage)
            self.println("[INFO] Node '${nodeName}' was marked as failed")
        } catch (e) {
            self.println("[ERROR] Failed to mark node '${nodeName}' as offline")
            self.println(e)
            throw e
        }
    }

    static def sendExceptionToSlack(Object self, String jobName, String buildNumber, String buildUrl, String webhook, String channel, String message)
    {
        try {
            def slackMessage = [
                attachments: [[
                    "title": "${jobName} [${buildNumber}]",
                    "title_link": "${buildUrl}",
                    "color": "#720000",
                    "text": message
                ]],
                channel: channel
            ]
            self.httpRequest(
                url: webhook,
                httpMode: 'POST',
                requestBody: JsonOutput.toJson(slackMessage)
            )
            self.println("[INFO] Exception was sent to Slack")
        } catch (e) {
            self.println("[ERROR] Failed to send exception to Slack")
            self.println(e)
        }
    }

    static def publishReport(Object self, String buildUrl, String reportDir, String reportFiles, String reportName, String reportTitles)
    {
        self.publishHTML([allowMissing: false,
                     alwaysLinkToLastBuild: false,
                     keepAll: true,
                     reportDir: reportDir,
                     reportFiles: reportFiles,
                     // TODO: custom reportName (issues with escaping)
                     reportName: reportName,
                     reportTitles: reportTitles])
        try {
            self.httpRequest(
                url: "${buildUrl}/${reportName.replace('_', '_5f').replace(' ', '_20')}/",
                authentication: 'jenkinsCredentials',
                httpMode: 'GET'
            )
            self.println("[INFO] Report exists")
        } catch(e) {
            self.println("[ERROR] Can't access report")
            throw new Exception("Can't access report", e)
        }
    }

    static def deepcopyCollection(Object self, def collection) {
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(collection);
        oos.flush()
        def bin = new ByteArrayInputStream(bos.toByteArray())
        def ois = new ObjectInputStream(bin)
        return ois.readObject()
    }

    static def getReportFailReason(String exceptionMessage) {
        if (!exceptionMessage) {
            return "Failed to build report."
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        if (exitCode && exitCode < 0) {
            switch(exitCode) {
                case -1:
                    return "Failed to build summary report."
                    break
                case -2:
                    return "Failed to build performance report."
                    break
                case -3:
                    return "Failed to build compare report."
                    break
                case -4:
                    return "Failed to build local reports."
                    break
                case -5:
                    return "Several plugin versions"
                    break
            }
        }
        return "Failed to build report."
    }

    static def isReportFailCritical(String exceptionMessage) {
        if (!exceptionMessage) {
            return true
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        // Failed to build summary report and unexpected fails
        return exitCode >= -1
    }
}