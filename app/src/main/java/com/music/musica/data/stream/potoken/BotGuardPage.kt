package com.music.musica.data.stream.potoken

/**
 * Self-contained HTML/JS harness executed inside an isolated, network-blocked Android WebView
 * to run YouTube's BotGuard interpreter and mint per-session/per-video Proof-of-Origin tokens.
 */
internal const val BOTGUARD_PAGE_HTML = """<!DOCTYPE html>
<html lang="en"><head><title></title><script>
    var bgVmFunctions = null;
    var bgVm = null;
    var bgProgram = null;
    var poTokenMinter = null;

    function loadBotGuard(challengeData) {
      bgVm = window[challengeData.globalName];
      bgProgram = challengeData.program;
      bgVmFunctions = null;

      if (!bgVm) throw new Error('[BotGuard]: VM not found in global object');
      if (!bgVm.a) throw new Error('[BotGuard]: Program entrypoint missing');

      var vmFunctionsCallback = function (
        asyncSnapshotFunction,
        shutdownFunction,
        passEventFunction,
        checkCameraFunction
      ) {
        bgVmFunctions = {
          asyncSnapshotFunction: asyncSnapshotFunction,
          shutdownFunction: shutdownFunction,
          passEventFunction: passEventFunction,
          checkCameraFunction: checkCameraFunction
        };
      };

      try {
        bgVm.a(bgProgram, vmFunctionsCallback, true, undefined, function () {}, [ [], [] ]);
      } catch (e) {
        throw new Error('[BotGuard]: Program execution failed: ' + e.message);
      }

      return new Promise(function (resolve, reject) {
        var attempts = 0;
        var maxAttempts = 10000;
        var checkInterval = setInterval(function () {
          if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) {
            clearInterval(checkInterval);
            resolve({ vmFunctions: bgVmFunctions, vm: bgVm, program: bgProgram });
          } else if (attempts >= maxAttempts) {
            clearInterval(checkInterval);
            reject(new Error('[BotGuard]: Timed out waiting for asyncSnapshotFunction'));
          }
          attempts++;
        }, 1);
      });
    }

    function snapshot(botguard, args) {
      return new Promise(function (resolve, reject) {
        if (!botguard.vmFunctions || !botguard.vmFunctions.asyncSnapshotFunction) {
          return reject(new Error('[BotGuard]: asyncSnapshotFunction missing'));
        }
        try {
          botguard.vmFunctions.asyncSnapshotFunction(
            function (response) { resolve(response); },
            [
              args.contentBinding,
              args.signedTimestamp,
              args.webPoSignalOutput,
              args.skipPrivacyBuffer
            ]
          );
        } catch (e) {
          reject(new Error('[BotGuard]: Snapshot failed: ' + e.message));
        }
      });
    }

    function runBotGuard(challengeData) {
      var interpreterJs = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
      if (!interpreterJs) {
        throw new Error('[BotGuard]: Missing interpreter JavaScript');
      }
      new Function(interpreterJs)();

      var webPoSignalOutput = [];
      return loadBotGuard({
        globalName: challengeData.globalName,
        program: challengeData.program
      }).then(function (botguard) {
        return snapshot(botguard, { webPoSignalOutput: webPoSignalOutput });
      }).then(function (botguardResponse) {
        return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse };
      });
    }

    async function createPoTokenMinter(webPoSignalOutput, integrityToken) {
      var getMinter = webPoSignalOutput && webPoSignalOutput[0];
      if (typeof getMinter !== 'function') {
        throw new Error('PMD:Invalid - webPoSignalOutput[0] is not a function');
      }
      var minterResult = getMinter(integrityToken);
      var mintCallback = (minterResult && typeof minterResult.then === 'function')
        ? await minterResult
        : minterResult;

      if (typeof mintCallback !== 'function') {
        throw new Error('APF:Invalid - mintCallback is not a function');
      }
      poTokenMinter = mintCallback;
    }

    async function obtainPoToken(identifier) {
      if (!poTokenMinter) {
        throw new Error('MNT:NotInit - poTokenMinter is not initialized');
      }
      var mintResult = poTokenMinter(identifier);
      var result = (mintResult && typeof mintResult.then === 'function')
        ? await mintResult
        : mintResult;
      if (!(result instanceof Uint8Array)) {
        throw new Error('ODM:Invalid - result is not Uint8Array');
      }
      return result;
    }
</script></head><body></body></html>
"""
