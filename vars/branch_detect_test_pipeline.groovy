def call() {
  node("ANDREY_A") {
    stage('PreBuild') {
      echo "Prebuld"
      echo "${BRANCH_NAME}"
      outputEnvironmentInfo("Windows")
      
      checkout([$class: 'GitSCM',
                userRemoteConfigs: [[url: 'https://github.com/luxteam/branch_detect_test.git']]])
      
      bat"""
      echo %CD%
      """
    }
    stage('Build') {
      echo "Build"
    }
    stage('Deploy') {
      echo "Deploy"
    }
  }
}
