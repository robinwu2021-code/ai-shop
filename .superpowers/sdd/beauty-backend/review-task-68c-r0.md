# Task68C exact2script independent R0 review
Reviewer /root/review_task68c_finite_runner; root transcribed final verdict/findings.
Spec compliance FAIL; task quality NEEDS_FIXES. Critical0/Important2/Minor1. Full128KBdiff reviewed; both finalsourceSHAs match freeze. No tests/imports/JVM/build/native/network/writes by reviewer.

I1 Important: Genuine old origins always fail shim string comparison. Runner1616 expected uses NodepathToFileURL.href file:///;1158actualJavaCodeSource.location.toURI.toString standard directoryfile:/;1163exactStringequality yieldsfalse forcorrectclasses and firstproducerneverstarts. JDK21installedsources staticallyconfirm URLClassPath->ParseUtil.fileToEncodedURL->newURL(file,emptyhost,path), URLhandler omits//foremptyauthority, URL.toURI buildsURIfromtoString. Consumer1473recomputesNodeexpected, doesnotfixmismatch. Normalize both sides to samecanonicalfileidentity, retaining independentexpectations/actualobservations.

I2 Important: Accepted runtime directories permit unpinned additions before launch.1409readAccepted verifiesonlylistedfiles;1466runtimeacceptance neverreconciles completecurrentdriver/oldproducer directories withpins. OldCPchecksselectedorigins/duplicates butnotenforceeveryenumeratedleafaccepted. Those prefixes prepend1780, so newhelperclass/service/configresource entersruntime withoutchanginglistedpin;1951perprocessverification alsolistedonly. Breakscompleteacceptedclosure. Re-enumerateEVERYeffective directory, rejectextra/missing/symlinkleaves; applyuniquenesschecks fullLAUNCHCP includinggeneratedprefixes beforeandafterexecution.

M1 Minor: recordedSKIPPED1282hasstarted=false;XML1319missing-start branch emitsERRORbefore skipbranch. Failsclosed/JSONstatuscorrect butXMLlifecyclewrong. Handle actualskipsbeforemissingstarterror. Nonblocking; finalwholebranchreviewtriage.

Strengths: exactsixstages/actions/prepareNOT_RUN/Task68gates/53methodpartition; existingm2a/m2b bodiesunchanged. Separate genuineold/goldens, actualmapper/launchersuffix/dependencyexporter/isolatedoldfixture; schemaseven/tenbindings/PLATFORM/fouroriginal/225s; finiteJUnit/events/container/process/XML;UUIDcleanupfatalerrors;15newNodevalidators useful.
Focusedchecks: actualB2producer outputfieldsmatchpendingEvidence;actuallegacyMapperfieldtargetcorrect;actual213entryclasspathstaticZIP/directoryindexZEROduplicateclassnames (riskcleared).
UNEXECUTEDruntime: materialization/oldbootstrap/timing/JUnitlifecycle/cleanup. Afterfixes+compile unifiedB runsall37Node(beforefix)/53JUnit/genuineprep/failurecleanup. No phaseB/deployapproval.
