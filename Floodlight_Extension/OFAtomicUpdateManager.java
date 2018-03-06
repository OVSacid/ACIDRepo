package net.floodlightcontroller.core.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugevent.IDebugEventService;
import net.floodlightcontroller.routing.FlowRouteInfo;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.FlowModUtils;

import org.projectfloodlight.openflow.protocol.OFBarrierRequest;
import org.projectfloodlight.openflow.protocol.OFBarrierRequest.Builder;
import org.projectfloodlight.openflow.protocol.OFBundleAddMsg;
import org.projectfloodlight.openflow.protocol.OFBundleCtrlMsg;
import org.projectfloodlight.openflow.protocol.OFBundleCtrlType;
import org.projectfloodlight.openflow.protocol.OFFactory;
import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowDelete;
import org.projectfloodlight.openflow.protocol.OFFlowDeleteStrict;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModify;
import org.projectfloodlight.openflow.protocol.OFFlowModifyStrict;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.BundleId;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OFAtomicUpdateManager implements IOFAtomicUpdate, IFloodlightModule {
	protected static Logger logger = LoggerFactory.getLogger(OFAtomicUpdateManager.class);
	
	protected static final int MMA_FLOW_ENTRY_HIGH_PRIORITY = 12;
	protected static final int MMA_FLOW_ENTRY_PRIORITY = 11;
	protected static final int MMA_FLOW_ENTRY_LOW_PRIORITY = 9;
	protected static final int MMA_FLOW_ENTRY_IDLE_TIMEOUT_ACTIVE = 1;
	protected static final int MMA_FLOW_ENTRY_HARD_TIMEOUT_ACTIVE = 10000;
	protected static final int MMA_FLOW_ENTRY_IDLE_TIMEOUT_PROACTIVE = 10000;
	protected static final int MMA_FLOW_ENTRY_HARD_TIMEOUT_PROACTIVE = 10000;
	
	protected static final int MIN_NUM_OF_THREADS = 4;
	protected static final int MAX_NUM_OF_THREADS = 10;
	protected static final int THREAD_QUEUE_SIZE = 500;
	
	// private ISyncService syncService;
	public static final String MODULE_NAME = "atomicitymanager";
	
	// Module Dependencies
	IFloodlightProviderService floodlightProvider;
	IDebugEventService debugEventService;
	IDebugCounterService debugCounterService;
	IOFSwitchService switchService;
	IOFReservationUpdate reservationService;
	
	ExecutorService executor = null;
	ScheduledThreadPoolExecutor executorPool = null;
		
	protected static final int RESERVATION_IDLE_TIMEOUT = 10000;
	protected static final int RESERVATION_HARD_TIMEOUT = 10000;

	public OFAtomicUpdateManager() {
		// executor = Executors.newFixedThreadPool(10);
		
	}
	
	private class RejectedExecutionHandlerImpl implements RejectedExecutionHandler {

	    @Override
	    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
	        System.out.println(r.toString() + " is rejected");
	    }

	}
	
	public void scheduleTask(Thread task, long delay) {
		// executorPool.execute(task);
		executorPool.schedule(task, delay, TimeUnit.MILLISECONDS);
	}
			
	public String toString() {
		return "AI";
	}
	
	public void swVoteLock (DatapathId swId, int updateID, FlowRouteInfo[] routeToInstall) {
		IOFSwitch sw = switchService.getSwitch(swId);
		
		if (sw != null) {
			OFFactory myFactory = sw.getOFFactory();
			
			// Bundle Control
	        OFBundleCtrlMsg bundleControl = myFactory.buildBundleCtrlMsg()
	        		.setXid(updateID)
	        		.setBundleId(BundleId.of(updateID))
	        		.setBundleCtrlType(OFBundleCtrlType.OPEN_REQUEST)
	        		.build();
	        sw.write(bundleControl);
	        
	        // Create Lock primitive = FLOW_MOD with command MODIFY_STRICT and table_id 195 (0xc3 in the OVS)
	        ArrayList<OFAction> actions = null;
			Match match = null;
			
			Match.Builder mb = myFactory.buildMatch();
			mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
			match = mb.build();
			
			actions = new ArrayList<OFAction>(); 
			actions.add(myFactory.actions().output(OFPort.CONTROLLER, Integer.MAX_VALUE));
			
			List<OFInstruction> instructions = new ArrayList<OFInstruction>();
			instructions.add(myFactory.instructions().buildApplyActions().setActions(actions).build());
			
			OFFlowModifyStrict flow = myFactory.buildFlowModifyStrict()
					.setCookie(U64.ofRaw(11))
					.setTableId(TableId.of(195))
					.setMatch(match)
					.setInstructions(instructions)
					.setBufferId(OFBufferId.NO_BUFFER)
					.build();
			
			// Add Lock primitive to the bundle
			OFBundleAddMsg bundleAdd = myFactory.buildBundleAddMsg()
					.setXid(flow.getXid())
	        		.setBundleId(BundleId.of(updateID))
	        		.setData(flow)
	        		.build();
			
			sw.write(bundleAdd);
			
			addNetworkUpdateVoteLock(updateID, sw.getId());
	        
			// Bundle Commit
	        bundleControl = myFactory.buildBundleCtrlMsg()
	        		.setBundleId(BundleId.of(updateID))
	        		.setBundleCtrlType(OFBundleCtrlType.COMMIT_REQUEST)
	        		.build();
	        sw.write(bundleControl);
	        
	        // round-trip time measurement
	        // System.out.println("-------------> voteLock sw: " + sw.getId().toString() + ", token: " + updateID + ", time: " + System.nanoTime());
	        //AIMeasurements.getInstance().addAIMeasurements(AITimestampType.BUNDLE_SENT, System.nanoTime(), updateID, sw.getId().toString(), -1);
		} else {
			throw new Error("Error in (swVoteLock) ---- Datapath " + swId.toString() + " does not exist!");
		}
	}
	
	public void voteLock(List<FlowRouteInfo[]> controlLogic, int updateID, IOFSwitch sw, OFPacketIn pi) {
		String node = controlLogic.get(0)[0].srcSW + "->" + controlLogic.get(0)[0].dstSW;
		List<Integer> updateIds = new ArrayList<Integer>();
		
		for (int i = 0; i < controlLogic.size(); i++) {
			updateID = -1;
			LinkedHashSet<DatapathId> switchList = controlLogic.get(i)[0].getCoreSwitchList();
			
			int max = 65535;
			int min = 0;
			
			if (updateID < 0) {
				Random rand = new Random();
				updateID = rand.nextInt((max - min) + 1) + min;
				while (reservationService.getNetworkUpdate(updateID) != null) {
					updateID = rand.nextInt((max - min) + 1) + min;
				}
	 		}
			
			reservationService.addPathReservationAttempt(updateID, switchList, controlLogic.get(i));
			reservationService.updateIdReattemptCounter(updateID);
			
			updateIds.add(updateID);
		}
		
		for (int i = 0; i < controlLogic.size(); i++) {
			reservationService.addFirstPacketIn(updateIds.get(i), sw, pi);
			
			/* AIMeasurements.getInstance().addAIMeasurements(AITimestampType.VOTE_LOCK, System.currentTimeMillis(), 
					updateIds.get(i), node, reservationService.getSwitchesForUpdateId(updateIds.get(i)).size()); */
			
			List<DatapathId> switchesForToken = new ArrayList<DatapathId>(); 
			setSwitchList(switchesForToken, reservationService.getSwitchesForUpdateId(updateIds.get(i)));
			
			// If a route alternative exists, perform VoteLock only over non-overlapping set of the switches
			if (controlLogic.size() > 1) {
				switchesForToken.removeAll(reservationService.getSwitchesForUpdateId(updateIds.get((i+1)%2)));
			}
			
			for (DatapathId swId : switchesForToken) {
				swVoteLock(swId, updateIds.get(i), reservationService.getNetworkUpdate(updateIds.get(i)));
			}
		}
	}
	
	private void setSwitchList(List<DatapathId> list, Set<DatapathId> origList) {
		for (DatapathId sw : origList) {
			list.add(sw);
		}
	}

	public void commitNetworkUpdate(int updateId, boolean commit) {
		System.out.println(">>>>>>>>> commitNetworkUpdate, reservation token: " + updateId + ", COMMIT: " + commit);
		
		FlowRouteInfo[] networkUpdate = reservationService.getNetworkUpdate(updateId);
		
		LinkedHashSet<DatapathId> switchList = networkUpdate[0].getCoreSwitchList();
		
		for (DatapathId sw : switchList) {
			commitNetworkUpdateForSwitch(updateId, sw, commit);
		}
		
		if (commit) {
			pushFirstPacketIn(updateId);
		} else {
			System.out.println(">>>>>>>>> commitNetworkUpdate, reservation token: " + updateId + ", ROLLBACK: " + commit);
		}
	}
	
	private void pushFirstPacketIn(int updateId) {
		HashMap<OFPacketIn,IOFSwitch> firstPacketIn = reservationService.getFirstPacketIn(updateId);
		
		OFPacketIn packetIn = firstPacketIn.keySet().iterator().next();
		IOFSwitch sw = firstPacketIn.get(packetIn);
		
		OFBarrierRequest.Builder buildBarrierRequest = sw.getOFFactory().buildBarrierRequest();		
		sw.write(buildBarrierRequest.build());
		
		packetIn.getData();
		
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
			
		// set actions
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(sw.getOFFactory().actions().output(OFPort.TABLE, 0));
	        
        pob.setActions(actions);
        pob.setBufferId(OFBufferId.NO_BUFFER);
        pob.setData(packetIn.getData());
	    
        System.out.println("Send first packet in to sw: " + sw.getId().toString());
	    
        sw.write(pob.build());
	}

	public void addNetworkUpdateVoteLock(int token, DatapathId swId) {
		FlowRouteInfo[] networkUpdate = reservationService.getNetworkUpdate(token);
				
		for (int i=0; i< networkUpdate.length; i++) {
			addFlowEntryToBundle(networkUpdate[i].route, networkUpdate[i].match, OFFlowModCommand.ADD, true, token, swId);
		}
	}
	
	public void commitNetworkUpdateForSwitch(int updateId, DatapathId sw, boolean commit) {
		// System.out.println(">>>>>>>>> Free switches " + switchList + ", reservation token: " + token);
		// Create Lock primitive = FLOW_MOD with command MODIFY_STRICT and table_id 195 (0xc3 in the OVS)
		// System.out.println(">>>>>>>>> commitNetworkUpdate, reservation token: " + updateId + ", COMMIT: " + commit + ", sw: " + sw.toString());
		
        IOFSwitch mySwitch = switchService.getSwitch(sw);
        OFFactory myFactory = mySwitch.getOFFactory();
        
        ArrayList<OFAction> actions = null;
		Match match = null;
		
		Match.Builder mb = myFactory.buildMatch();
		mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
		match = mb.build();
		
		actions = new ArrayList<OFAction>();
		actions.add(myFactory.actions().output(OFPort.CONTROLLER, Integer.MAX_VALUE));
		
		List<OFInstruction> instructions = new ArrayList<OFInstruction>();
		instructions.add(myFactory.instructions().buildApplyActions().setActions(actions).build());
		
		OFFlowAdd flow = myFactory.buildFlowAdd()
				.setCookie(U64.ofRaw(11))
				.setTableId(TableId.of(195))
				.setMatch(match)
				.setInstructions(instructions)
				.setBufferId(OFBufferId.NO_BUFFER)
				.setXid(updateId)
				.build();
		
		if (commit) {
			OFFlowDelete flowDelete = FlowModUtils.toFlowDelete(flow);
			mySwitch.write(flowDelete);
		} else {
			OFFlowDeleteStrict flowDeleteStrict = FlowModUtils.toFlowDeleteStrict(flow);
			mySwitch.write(flowDeleteStrict);
		}
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFAtomicUpdate.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
		IFloodlightService> m =
		new HashMap<Class<? extends IFloodlightService>,
		IFloodlightService>();
		// We are the class that implements the service
		m.put(IOFAtomicUpdate.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDebugEventService.class);
		l.add(IDebugCounterService.class);
		// l.add(ISyncService.class);
		l.add(IOFSwitchService.class);
		l.add(IOFReservationUpdate.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		ThreadFactory threadFactory = Executors.defaultThreadFactory();
		RejectedExecutionHandlerImpl rejectionHandler = new RejectedExecutionHandlerImpl();
		
		// this.executorPool = (ScheduledThreadPoolExecutor) new ThreadPoolExecutor(MIN_NUM_OF_THREADS, MAX_NUM_OF_THREADS, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(MAX_NUM_OF_THREADS), threadFactory, rejectionHandler);
		
		this.executorPool = new ScheduledThreadPoolExecutor(MAX_NUM_OF_THREADS, threadFactory, rejectionHandler);
		
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		debugEventService = context.getServiceImpl(IDebugEventService.class);
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		// syncService = context.getServiceImpl(ISyncService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		reservationService = context.getServiceImpl(IOFReservationUpdate.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
	}

	public void pushFlowEntryToAccessSw(Route route, Match match, OFFlowModCommand flowModCommand, int priority, boolean active){
		List<NodePortTuple> npList;
		OFPort outPort = null;

		npList = route.getPath();
		ArrayList<OFAction> actions = null;

		for (int i = npList.size()-2; i >= 0; i-=2) {
			if ((i == 0) || (i < (npList.size()-1))){
				DatapathId sw = npList.get(i).getNodeId();
				
				// Skip core switches
				if (sw.toString().contains("0c:00:00")) {
					continue;
				}
				
				IOFSwitch mySwitch = switchService.getSwitch(sw);
				OFFactory myFactory = mySwitch.getOFFactory();
				
				if ((i+1) < npList.size()) {
					outPort = npList.get(i+1).getPortId();
				} else {
					return;
				}
				
				if(match != null){
					OFFlowAdd flow;

					actions = new ArrayList<OFAction>(); 
					actions.add(myFactory.actions().buildOutput()  
							.setPort(outPort)  
							.build());
					
					List<OFInstruction> instructions = new ArrayList<OFInstruction>();
					instructions.add(myFactory.instructions().buildApplyActions().setActions(actions).build());
					
					if (active) {
						flow = myFactory.buildFlowAdd()
								.setCookie(route.getId().getCookie())
								.setMatch(match)
								.setTableId(TableId.of(0))
								.setInstructions(instructions)
								.setBufferId(OFBufferId.NO_BUFFER)
								.setPriority(priority)
								.setIdleTimeout(MMA_FLOW_ENTRY_IDLE_TIMEOUT_ACTIVE)
								.setHardTimeout(MMA_FLOW_ENTRY_HARD_TIMEOUT_ACTIVE)
								.build();						
					} else {
						flow = myFactory.buildFlowAdd()
								.setCookie(route.getId().getCookie())
								.setMatch(match)
								.setTableId(TableId.of(0))
								.setInstructions(instructions)
								.setBufferId(OFBufferId.NO_BUFFER)
								.setPriority(priority)
								.setIdleTimeout(MMA_FLOW_ENTRY_IDLE_TIMEOUT_PROACTIVE)
								.setHardTimeout(MMA_FLOW_ENTRY_HARD_TIMEOUT_PROACTIVE)
								.build();
					}
					// need to build flow mod based on what type it is. Cannot set command later
					
					switch (flowModCommand) {
					case ADD:
						// System.out.println(">>>>>>>>>>>>>>>>>> (pushFlowEntryToAccessSw) ADD FLOW:" + flow.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						mySwitch.write(flow); 
						break;
					case DELETE:
						OFFlowDelete flowDelete = FlowModUtils.toFlowDelete(flow);
						// System.out.println(">>>>>>>>>>>>>>>>>> (pushFlowEntry) DELETE FLOW:" + flowDelete.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						mySwitch.write(flowDelete);
						break;
					case DELETE_STRICT:
						OFFlowDeleteStrict flowDelete_strict = FlowModUtils.toFlowDeleteStrict(flow);
						//System.out.println(">>>>>>>>>>>>>>>>>> (pushFlowEntry) DELETE_STRICT FLOW:" + flowDelete_strict.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						mySwitch.write(flowDelete_strict); 
						break;
					case MODIFY:
						OFFlowModify flowModify = FlowModUtils.toFlowModify(flow);
						//System.out.println(">>>>>>>>>>>>>>>>>> (pushFlowEntry) MODIFY FLOW:" + flowModify.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						mySwitch.write(flowModify); 
						break;
					default:
						System.out.println(">>>>>>>>>>>>>>>>>> (pushFlowEntry) Could not decode OFFlowModCommand)");        
						break;			
					}
				}
			}
		}		
	}	
	
	public void addFlowEntryToBundle(Route route, Match match, OFFlowModCommand flowModCommand, boolean active, int updateID, DatapathId swId){
		List<NodePortTuple> npList;
		OFPort outPort = null;
		int priority = OFAtomicUpdateManager.MMA_FLOW_ENTRY_PRIORITY;
		
		npList = route.getPath();
		ArrayList<OFAction> actions = null;

		for (int i = npList.size()-2; i >= 0; i-=2) {
			// (01.07.2015 - CM)
			// The main reason for this IF is to 
			// avoid the case when controller delivers wrong
			if ((i == 0) || (i < (npList.size()-1))){
				DatapathId sw = npList.get(i).getNodeId();
				if (!sw.toString().equals(swId.toString())) {
					continue;
				}
				
				IOFSwitch mySwitch = switchService.getSwitch(sw);
				OFFactory myFactory = mySwitch.getOFFactory();
				
				if ((i+1) < npList.size()) {
					outPort = npList.get(i+1).getPortId();
				} else {
					return;
				}
				
				if (match != null) {
					OFFlowAdd flow;

					actions = new ArrayList<OFAction>(); 
					actions.add(myFactory.actions().buildOutput()  
							.setPort(outPort)  
							.build());
					
					List<OFInstruction> instructions = new ArrayList<OFInstruction>();
					instructions.add(myFactory.instructions().buildApplyActions().setActions(actions).build());
					
					// Set<OFFlowModFlags> sfmf = new HashSet<OFFlowModFlags>();
					// sfmf.add(OFFlowModFlags.SEND_FLOW_REM);
					// sfmf.add(OFFlowModFlags.CHECK_OVERLAP);
					
					if (active) {
						flow = myFactory.buildFlowAdd()
								.setCookie(route.getId().getCookie())
								.setMatch(match)
								.setTableId(TableId.of(0))
								.setInstructions(instructions)
								.setBufferId(OFBufferId.NO_BUFFER)
								.setPriority(priority)
								.setIdleTimeout(MMA_FLOW_ENTRY_IDLE_TIMEOUT_ACTIVE)
								.setHardTimeout(MMA_FLOW_ENTRY_HARD_TIMEOUT_ACTIVE)
								// .setFlags(sfmf)
								.build();
					} else {
						flow = myFactory.buildFlowAdd()
								.setCookie(route.getId().getCookie())
								.setMatch(match)
								.setTableId(TableId.of(0))
								.setInstructions(instructions) 
								.setBufferId(OFBufferId.NO_BUFFER)
								.setPriority(priority)
								.setIdleTimeout(MMA_FLOW_ENTRY_IDLE_TIMEOUT_PROACTIVE)
								.setHardTimeout(MMA_FLOW_ENTRY_HARD_TIMEOUT_PROACTIVE)
								// .setFlags(sfmf)
								.build();
					}
					// need to build flow mod based on what type it is. Cannot set command later
					
					switch (flowModCommand) {
					case ADD:
						// System.out.println("(addFlowEntryToBundle) ADD FLOW:" + flow.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						
						OFBundleAddMsg bundleAdd = myFactory.buildBundleAddMsg()
								.setXid(flow.getXid())
								.setBundleId(BundleId.of(updateID))
								.setData(flow)
								.build();
				
						mySwitch.write(bundleAdd);
						break;
					case DELETE:
						OFFlowDelete flowDelete = FlowModUtils.toFlowDelete(flow);
						// System.out.println("(addFlowEntryToBundle) DELETE FLOW:" + flowDelete.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						
						OFBundleAddMsg bundleDelete = myFactory.buildBundleAddMsg()
								.setXid(flowDelete.getXid())
								.setBundleId(BundleId.of(updateID))
				        		.setData(flowDelete)
				        		.build();
						
						mySwitch.write(bundleDelete);
						break;
					case DELETE_STRICT:
						OFFlowDeleteStrict flowDelete_strict = FlowModUtils.toFlowDeleteStrict(flow);
						// System.out.println("(addFlowEntryToBundle) DELETE_STRICT FLOW:" + flowDelete_strict.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						
						OFBundleAddMsg bundleDeleteStrict = myFactory.buildBundleAddMsg()
								.setXid(flowDelete_strict.getXid())
								.setBundleId(BundleId.of(updateID))
				        		.setData(flowDelete_strict)
				        		.build();
						
						mySwitch.write(bundleDeleteStrict);
						break;
					case MODIFY:
						OFFlowModify flowModify = FlowModUtils.toFlowModify(flow);
						// System.out.println("(addFlowEntryToBundle) MODIFY FLOW:" + flowModify.toString() + " Route:" + route.toString() + " SW:" + mySwitch.getId().toString());        
						
						OFBundleAddMsg bundleModify = myFactory.buildBundleAddMsg()
								.setXid(flowModify.getXid())
				        		.setBundleId(BundleId.of(updateID))
				        		.setData(flowModify)
				        		.build();
						
						mySwitch.write(bundleModify);
						break;
					default:
						System.out.println("(pushFlowEntry) Could not decode OFFlowModCommand)");        
						break;			
					}
				}
			}
		}		
	}
}