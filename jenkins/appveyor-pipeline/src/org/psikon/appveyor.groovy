package org.psikon

def appveyor_download_artifacts(accountName, projectSlug, buildVersion, targetDir) {

  echo '[APPVEYOR] Downloading artifacts';

  def content = httpRequest(
    url: "https://ci.appveyor.com/api/projects/${accountName}/${projectSlug}/build/${buildVersion}",
    customHeaders: [
      [name: 'Accept', value: 'application/json']
    ]
  );
  echo groovy.json.JsonOutput.prettyPrint(content.getContent());
  def build_obj = new groovy.json.JsonSlurperClassic().parseText(content.getContent());

  def job_id = build_obj.build.jobs[0].jobId;

  def artifact_response = httpRequest(
    url: "https://ci.appveyor.com/api/buildjobs/${job_id}/artifacts",
    customHeaders: [
      [name: 'Accept', value: 'application/json']
    ]
  );

  def artifact_response_content = artifact_response.getContent();
  echo artifact_response_content;

  build_obj = new groovy.json.JsonSlurperClassic().parseText(artifact_response_content);

  build_obj.each {
    echo "[APPVEYOR] Artifact found: ${it.fileName}";
    def f = new File(it.fileName);
    def fn = f.getName();
    def encodedFn = java.net.URLEncoder.encode(it.fileName, 'UTF-8');
    def url = "https://ci.appveyor.com/api/buildjobs/${job_id}/artifacts/${encodedFn}";
    echo "[APPVEYOR] Downloading ${url}"
    sh(script: """mkdir -p ${targetDir} && wget -O ${targetDir}/${fn} ${url}""");
    echo "[APPVEYOR] Artifact downloaded to ${targetDir}/${fn}"
  };
}

def appveyor_start_build(appveyorToken, accountName, projectSlug, branch, commitId) {
  echo '[APPVEYOR] Starting appveyor job';

  def request = [:]
  request['accountName'] = accountName;
  request['projectSlug'] = projectSlug;
  request['environmentVariables'] = [:];
  request['environmentVariables']['JENKINS_BUILD_NUMBER'] = env.BUILD_NUMBER;

  if (branch.startsWith('PR')) {
    echo '[APPVEYOR] Building a pull request';
    def pr = branch.split('-')[1];
    request['pullRequestId'] = pr;
  } else {
    echo "[APPVEYOR] Building ${branch} : ${commitId}";
    request['branch'] = branch;
    request['commitId'] = commitId;
  }

  def requestBody = new groovy.json.JsonBuilder(request).toPrettyString();

  // echo "[APPVEYOR] Request body: ${request_body}";
  def build_response = httpRequest(
    url: "https://ci.appveyor.com/api/account/${accountName}/builds",
    httpMode: 'POST',
    customHeaders: [
      [name: 'Authorization', value: "Bearer ${appveyorToken}"],
      [name: 'Content-type', value: 'application/json']
    ],
    requestBody: requestBody
  )

  def content = build_response.getContent();

  def build_obj = new groovy.json.JsonSlurperClassic().parseText(content)
  echo "[APPVEYOR] Appveyor build number: ${build_obj.buildNumber}";
  echo "[APPVEYOR] Appveyor build version: ${build_obj.version}";

  return build_obj;
}

def appveyor_build_status(appveyorToken, accountName, projectSlug, buildVersion) {
  def status_response = httpRequest(
      url: "https://ci.appveyor.com/api/projects/${accountName}/${projectSlug}/build/${buildVersion}",
      httpMode: 'GET',
      customHeaders: [
          [name: 'Authorization', value: "Bearer ${appveyorToken}"],
          [name: 'Accept', value: 'application/json']
      ]
  )

  def status_content = status_response.getContent()
  def build_data = new groovy.json.JsonSlurperClassic().parseText(status_content)

  return build_data.build.status;

}

def appveyor_wait(appveyorToken, accountName, projectSlug, buildVersion) {
  def appveyorFinished = false;

  def buildStatus = ""

  while (appveyorFinished == false) {
    buildStatus = appveyor_build_status(TOKEN, accountName, projectSlug, buildVersion);
    if (buildStatus == "success" || buildStatus == "error" || buildStatus == "failed" || buildStatus == 'cancelled') {
      echo "[APPVEYOR] Finished. Result is ${buildStatus} ";
      appveyorFinished = true;
    } else {
      echo "[APPVEYOR] Build status is ${buildStatus}";
      sleep(30);
    }
  }

  if (buildStatus != "success") {
    error("Appveyor failed to build! Version: ${buildVersion} - Status: ${buildStatus}")
  }
}
