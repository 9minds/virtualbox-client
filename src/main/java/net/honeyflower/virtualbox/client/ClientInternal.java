 package net.honeyflower.virtualbox.client;

import java.util.ArrayList;
import java.util.List;

import org.virtualbox_6_0.CleanupMode;
import org.virtualbox_6_0.Holder;
import org.virtualbox_6_0.IConsole;
import org.virtualbox_6_0.IMachine;
import org.virtualbox_6_0.IMedium;
import org.virtualbox_6_0.IProgress;
import org.virtualbox_6_0.ISession;
import org.virtualbox_6_0.ISnapshot;
import org.virtualbox_6_0.ISystemProperties;
import org.virtualbox_6_0.IVirtualBox;
import org.virtualbox_6_0.LockType;
import org.virtualbox_6_0.MachineState;
import org.virtualbox_6_0.VBoxException;
import org.virtualbox_6_0.VirtualBoxManager;
import org.virtualbox_6_0.jaxws.InvalidObjectFaultMsg;

import com.sun.xml.ws.client.ClientTransportException;

import lombok.extern.slf4j.Slf4j;
import net.honeyflower.virtualbox.client.constants.SystemPropertyKey;

@Slf4j
public class ClientInternal {
	
	private String username;
	private String password;
	private String url;
	
	/*static {
		System.setProperty(BindingProviderProperties.CONNECT_TIMEOUT, "2000");
		System.setProperty(BindingProviderProperties.REQUEST_TIMEOUT, "5000");
		
	}*/
	
	private VirtualBoxManager mgr;
	private ISession session;
	
	
	public ClientInternal(String username, String password, String url) {
		mgr = VirtualBoxManager.createInstance(null);
		this.username=username;
		this.password=password;
		this.url=url;
		init();
		
	}
	
	private void init() {
		try {
			mgr.disconnect();
		} catch (Exception e) {
			log.warn("was unable to disconnect : {}", e.getMessage());
		}
		
		mgr.connect(url, username, password);
		session = mgr.getSessionObject();
	}
	
	protected String importVM(String name) {
		IMachine vm = findVM(name);
		if (vm!=null) return vm.getId();

		return importVMByConfig(getProperty(SystemPropertyKey.DEFAULT_VMS_FOLDER) + '/' + name + '/' + name + ".vbox");
	}
	
	/**
	 * importing vm from predefined location
	 * @param configPath
	 * @return uuid of imported vm
	 */
	protected String importVMByConfig(String configPath) {
		IVirtualBox vbox = mgr.getVBox();
		IMachine vm = vbox.openMachine(configPath);
		vbox.registerMachine(vm);
		
		return vm.getId();
	}
	
	protected boolean deleteVM(IMachine vm) {
		
		log.debug("about to start vm '{}' deletetion", vm.getId());
		shutdownVM(vm);
		List<IMedium> mediums = vm.unregister(CleanupMode.DetachAllReturnHardDisksOnly);
		log.info("vm '{}' unregistered", vm.getId());
		log.debug("about to delete the following mediums : {} ", mediums);
		
		IProgress p = vm.deleteConfig(mediums);
		boolean result = watchProgress(mgr, p, 10000);
		log.debug("finished vm '{}' deletetion", vm.getId());
		return result;
	}
	
	protected boolean startVM(IMachine vm) {
		//vm.lockMachine(session,  LockType.Write);
		//vm = session.getMachine();
		boolean result = false;
		MachineState state = vm.getState();
		switch (state) {
		case Aborted:
		case Paused:
		case PoweredOff:
		case Saved:
			IProgress p = vm.launchVMProcess(session, "headless", "");
			result = watchProgress(mgr, p, 10000);
			session.unlockMachine();
			break;

		default:
			result = true;
			break;
		}
		
		return result;
	}
	
	/**
	 * attempts to stop specified vm
	 * if vm not in state of {@link MachineState.Paused} nor {@link MachineState.Running} we'll do full shutdown
	 * otherwise we'll do saveState behavior
	 * @param vm
	 * @return
	 */
	protected boolean stopVM(IMachine vm) {
		boolean result = false;
		try {
			
			MachineState state = vm.getState();
			switch (state) {
			case Running:
			case Paused:
				vm.lockMachine(session,  LockType.Shared);
				vm = session.getMachine();
				IProgress p = vm.saveState();
				result = watchProgress(mgr, p, 10000);
				session.unlockMachine();
				break;

			default:
				result = shutdownVM(vm);
				break;
			}
			
			
			
			//session.unlockMachine();
		} catch (Exception e) {
			log.warn("error during saving state", e);
			
		}
		return result;
	}
	
	protected boolean createSnapshotVM(IMachine vm, String snapshotName, String desciption) {
		vm.lockMachine(session, LockType.Shared);
		Holder<String> snapshotUuid = new Holder<String>();
		vm = session.getMachine();
		IProgress p = vm.takeSnapshot(snapshotName, desciption, true, snapshotUuid);
		boolean result = watchProgress(mgr, p, 10000);
		session.unlockMachine();
		return result;
	}
	
	protected List<String> getVMSnapshots(IMachine vm) {
		List<String> snaps = new ArrayList<String>(10);
		if (vm.getSnapshotCount() < 1) return snaps;
		
		ISnapshot root = vm.findSnapshot(null);
		traverseSnapshots(root, snaps);
		return snaps;
	}
	
	protected ISnapshot findSnapshot(IMachine vm, String snapshotName) {
		return vm.findSnapshot(snapshotName);
	}
	
	protected boolean restoreSnapshot(IMachine vm, ISnapshot snapshot) {
		log.info("starting snapshot restore for vm {}", vm.getName());
		shutdownVM(vm);
        log.debug("restoring snapshot {} for vm {}", snapshot.getName(), vm.getName());
        vm.lockMachine(session, LockType.Shared);
        vm = session.getMachine();
		IProgress p = vm.restoreSnapshot(snapshot);
		boolean result = watchProgress(mgr, p, 10000);
		session.unlockMachine();
		
		return result;
	}
	
	protected boolean deleteSnapshot(IMachine vm, ISnapshot snapshot) {
        log.debug("deleting snapshot '{}' for vm '{}'", snapshot.getName(), vm.getName());
        vm.lockMachine(session, LockType.Shared);
        vm = session.getMachine();
		IProgress p = vm.deleteSnapshot(snapshot.getId());
		boolean result = watchProgress(mgr, p, 10000);
		session.unlockMachine();
		
		return result;
	}
	
	protected boolean shutdownVM(IMachine vm) {
		boolean result = false;
		log.debug("shutting down vm {}", vm.getName());
		MachineState state = vm.getState();
		switch (state) {
		case Running:
		case Paused:
		case Stuck:
			vm.lockMachine(session,  LockType.Shared);
	        IConsole console = session.getConsole();
	        result = watchProgress(mgr, console.powerDown(), 10000);
	        //session.unlockMachine();
			break;

		default:
			result = true;
			break;
		}
		
		
        
        return result;
	}

	protected void diconnect() {
		mgr.cleanup();
	}
	
	private void traverseSnapshots(ISnapshot root, List<String> snaps) {
		snaps.add(root.getName());
		for (ISnapshot snapChild : root.getChildren()) {
			traverseSnapshots(snapChild, snaps);
        }
		
	}
	
	private boolean watchProgress(VirtualBoxManager mgr, IProgress p, long waitMillis) {
        long end = System.currentTimeMillis() + waitMillis;
        while (!p.getCompleted()) {
            // process system event queue
            mgr.waitForEvents(0);
            log.debug("processing task {} of {} operation, progress: {}", p.getId(), p.getDescription(), p.getPercent());
            // wait for completion of the task, but at most 200 msecs
            p.waitForCompletion(200);
            if (System.currentTimeMillis() >= end)
                return false;
        }
        log.info("finished processing task {}, result is: {}", p.getDescription(), p.getCompleted());
        return true;
    }

	protected IMachine findVM(String name) {
		IMachine vm = null;
		
		try {
			vm = mgr.getVBox().findMachine(name);
		} catch (VBoxException e) {
			log.warn(e.getMessage());
			if (e.getCause()!=null && e.getCause() instanceof InvalidObjectFaultMsg) {
				log.debug("got InvalidObjectFaultMsg, will try to reinit client");
				init();
				vm = mgr.getVBox().findMachine(name);
			}
		} catch (ClientTransportException e) {
			log.warn(e.getMessage());
			init();
			vm = mgr.getVBox().findMachine(name);
		}
		
		return vm;
	}
	
	protected String getProperty(SystemPropertyKey key) {
		String value = null;
		switch (key) {
		case DEFAULT_VMS_FOLDER:
			value = mgr.getVBox().getSystemProperties().getDefaultMachineFolder();
			break;

		default:
			break;
		}
		
		return value;
	}
	
	protected void setProperty(SystemPropertyKey key, String value) {
		ISystemProperties properties = mgr.getVBox().getSystemProperties();
		switch (key) {
		case DEFAULT_VMS_FOLDER:
			properties.setDefaultMachineFolder(value);
			break;

		default:
			break;
		}
	}

}
