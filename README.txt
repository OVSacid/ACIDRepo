2018-02-22

DISCLAIMER: The software extensions provided as part of this package are part of an ongoing research and development work. They are meant for experimentation, research and engineering as a proof-of-concept. The software produced by adding these files has not been extensively tested in operational environments and should not be used in production environments. The authors and their employer do not take any responsibility for any negative possible effects.

This README explains, how to add the Transactional Update support [PAPERREF] to an SDN environment consisting of a FloodLight SDN controller and OpenVSwitch SDN Switch. The necessary files are provided as part of this archive in ./floodlight and ./ovs respectively. It assumes that the reader is familiar with SDN, mininet, FloodLight and OVS in general. Once both patches are applied, the user needs to deploy, configure and start OVS and FloodLight on the system, e.g. within a Mininet environment.

Please check the file Forwarding.java for a usage example of the new service for transaction update.


************************ FLOODLIGHT ************************

To add the new service for transactional updates to the Floodlight controller, it is necessary to execute the following steps:

1. Download Floodlight version 1.2 (http://www.projectfloodlight.org/download/)
   and import it to a new Eclipse project

2. Apply the following patches to the existing java classes (this adds support for OpenFlow 1.4 and the OpenFlow bundles):
   
   2.1. FlowModUtils.patch

	copy the patch file to the folder PROJECT_NAME/src/main/java/net/floodlightcontroller/util/
	
	while in the folder, apply the patch file to FlowModUtils.java

	$> patch FlowModUtils.java FlowModUtils.patch

   2.2 OFSwitchHandshakeHandler.patch

	copy the patch file to folder PROJECT_NAME/src/main/java/net/floodlightcontroller/core/internal/

	while in the folder, apply the patch file to OFSwitchHandshakeHandler.java
	 $> patch OFSwitchHandshakeHandler.java OFSwitchHandshakeHandler.patch

3. From this folder ./floodlight, copy the following service-related java classes to the folder PROJECT_NAME/src/main/java/net/floodlightcontroller/core/internal/:

   3.1. IOFAtomicUpdate.java
   3.2. OFAtomicUpdateManager.java
   3.3. IOFReservationUpdate.java
   3.4. OFReservationUpdate.java
   3.5. SwitchVoteState.java

4. From this folder ./floodlight, copy the following java classes to the folder PROJECT_NAME/src/main/java/net/floodlightcontroller/routing/:

   4.1. FlowRouteInfo.java
   4.2. ForwardingBase.java (overwrite existing class -> YES)

5. From this folder ./floodlight, copy the following java classes to the folder PROJECT_NAME/src/main/java/net/floodlightcontroller/forwarding/:

   5.1. Forwarding.java (overwrite existing class -> YES)
 
6. From this folder ./floodlight, copy floodlightdefault.properties to PROJECT_NAME/src/main/resources/
   (overwrite existing file -> YES)

7. From this folder ./floodlight, copy net.floodlightcontroller.core.module.IFloodlightModule to PROJECT_NAME/src/main/resources/META-INF/services
   (overwrite existing file -> YES)



************************ OVS ************************

To apply the SS2PL patch to the OVS [PAPERREF], it is necessary to execute the following steps:

1. Download OpenVSwitch v2.8.0 (http://www.openvswitch.org//download/)
   and unpack it (e.g. to the folder openvswitch-2.8.0)

2. Apply the patch to the following files:

   2.1. openvswitch-2.8.0/include/openvswitch/ofp-errors.h
   2.2. openvswitch-2.8.0/ofproto/bundles.h
   2.3. openvswitch-2.8.0/ofproto/ofproto.c
   2.4. openvswitch-2.8.0/ofproto/ofproto-provider.h

   INSTRUCTION on patching - go to the folder containing the file that needs to be patched and apply the command:

	$> patch -p3 < openvswitch.patch

   NOTE: it might be necessary to manually enter the names of the files that need to be patched when asked "File to patch:"
			--------------------------
			|diff -Naur openvswitch-2.8.0/ofproto/bundles.h openvswitch-2.8.1/ofproto/bundles.h
			|--- openvswitch-2.8.0/ofproto/bundles.h        2018-02-13 14:23:26.716955930 +0100
			|+++ openvswitch-2.8.1/ofproto/bundles.h        2018-02-13 14:44:29.336128329 +0100
			--------------------------
			File to patch: bundles.h
			patching file bundles.h
			can't find file to patch at input line 31
			Perhaps you used the wrong -p or --strip option?
			The text leading up to this was:
			--------------------------
			|diff -Naur openvswitch-2.8.0/ofproto/ofproto.c openvswitch-2.8.1/ofproto/ofproto.c
			|--- openvswitch-2.8.0/ofproto/ofproto.c        2018-02-13 14:23:26.248947350 +0100
			|+++ openvswitch-2.8.1/ofproto/ofproto.c        2018-02-13 14:44:28.892120108 +0100
			--------------------------
			File to patch: ofproto.c
			patching file ofproto.c
			can't find file to patch at input line 499
			Perhaps you used the wrong -p or --strip option?
			The text leading up to this was:
			--------------------------
			|diff -Naur openvswitch-2.8.0/ofproto/ofproto-provider.h openvswitch-2.8.1/ofproto/ofproto-provider.h
			|--- openvswitch-2.8.0/ofproto/ofproto-provider.h       2018-02-13 14:23:26.284948010 +0100
			|+++ openvswitch-2.8.1/ofproto/ofproto-provider.h       2018-02-13 14:44:28.928120775 +0100
			--------------------------
			File to patch: ofproto-provider.h
			patching file ofproto-provide

3. Now, follow the usual compilation & installation for OVS in the patched folder and install this OVS on your system.
