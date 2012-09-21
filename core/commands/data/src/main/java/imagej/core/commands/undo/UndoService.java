package imagej.core.commands.undo;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import imagej.command.Command;
import imagej.command.CommandService;
import imagej.command.InvertableCommand;
import imagej.command.Unrecordable;
import imagej.data.Dataset;
import imagej.data.display.ImageDisplay;
import imagej.data.display.ImageDisplayService;
import imagej.display.Display;
import imagej.display.DisplayService;
import imagej.display.event.DisplayDeletedEvent;
import imagej.event.EventHandler;
import imagej.event.EventService;
import imagej.module.event.ModuleCanceledEvent;
import imagej.module.event.ModuleFinishedEvent;
import imagej.module.event.ModuleStartedEvent;
import imagej.plugin.Parameter;
import imagej.plugin.Plugin;
import imagej.service.AbstractService;
import imagej.service.Service;

// TODO
// This service is poorly named (recording something service is better)
// This service belongs some place other than ij-commands-data
// More undoable ops: zoom events, pan events, display creations/deletions,
//   setting of options values, dataset dimensions changing, setImgPlus(), etc.
// Also what about legacy plugin run results? (Multiple things hatched)
// Use displays rather than datasets (i.e. handle overlays too and other events)
// Multiple undo histories for different displays etc.
// ThreadLocal code for classToNotRecord. Nope, can't work.
// Reorganizing undo/redo history when in middle and command recorded
// Track display deleted events and delete related undo history 
// Make friendly for multithreaded access.
// Currently made to handle Datasets of ImageDisplays. Should be made to support
//   histories of arbitrary objects. The objects would need to support some
//   duplicate/save/restore interface.

// Later TODOs
// Support tools and gestures
// Grouping of many cammands as one undoable block
//   Create a Command that contains a list of commands to run. Record the undos
//   in one and the redos in another. Then add the uber command to the undo stack.

/**
 * 
 * @author Barry DeZonia
 *
 */
@Plugin(type = Service.class)
public class UndoService extends AbstractService {

	// -- constants --
	
	// TODO we really need max mem for combined histories and not max steps of
	// each history. But it will work as a test bed for now. ANd max should be
	// a settable value in some options plugin
	
	private static final int MAX_STEPS = 5;
	
	// -- Parameters --
	
	@Parameter
	private DisplayService displayService;
	
	@Parameter
	private ImageDisplayService imageDisplayService;
	
	@Parameter
	private CommandService commandService;
	
	@Parameter
	private EventService eventService;
	
	// -- working variables --
	
	private Map<Display<?>,History> histories;

	// TODO : SUPER DUPER HACK THAT WILL GO AWAY
	private Class<? extends Command> classToNotRecord;
	
	// -- service initialization code --
	
	@Override
	public void initialize() {
		histories = new HashMap<Display<?>,History>() ;
		subscribeToEvents(eventService);
	}

	// -- public api --
	
	/**
	 * Undoes the previous command associated with the given display.
	 * 
	 * @param interestedParty
	 */
	public void undo(Display<?> interestedParty) {
		History history = histories.get(interestedParty);
		if (history != null) history.doUndo();
	}
	
	/**
	 * Redoes the next command associated with the given display.
	 * 
	 * @param interestedParty
	 */
	public void redo(Display<?> interestedParty) {
		History history = histories.get(interestedParty);
		if (history != null) history.doRedo();
	}

	/**
	 * Clears the entire undo/redo cache for given display
	 */
	public void clearHistory(Display<?> interestedParty) {
		History history = histories.get(interestedParty);
		if (history != null) history.clear();
	}
	
	/**
	 * Clears the entire undo/redo cache for all displays
	 */
	public void clearAllHistory() {
		for (History hist : histories.values()) {
			hist.clear();
		}
	}
	
	// -- protected event handlers --
	
	@EventHandler
	protected void onEvent(ModuleStartedEvent evt) {
		Object theObject = evt.getModule().getDelegateObject();
		if (theObject instanceof Unrecordable) return;
		// TODO - this could be fraught with multithreaded issues
		if ((classToNotRecord != null) && (classToNotRecord.isInstance(theObject))){
			return;
		}
		if (theObject instanceof InvertableCommand) return; // record later
		if (theObject instanceof Command) {
			Display<?> display = displayService.getActiveDisplay();
			// FIXME HACK only datasets and imagedisplays supported right now
			if (!(display instanceof ImageDisplay)) return;
			Dataset dataset = imageDisplayService.getActiveDataset((ImageDisplay)display);
			if (dataset == null) return;
			UndoHelperPlugin snapshot = new UndoHelperPlugin();
			snapshot.setContext(getContext());
			snapshot.setSource(dataset);
			snapshot.run();
			Dataset backup = snapshot.getTarget();
			Map<String,Object> inputs = new HashMap<String, Object>();
			inputs.put("source", backup);
			inputs.put("target", dataset);
			findHistory(display).addUndo(UndoHelperPlugin.class, inputs);
		}
	}
	
	@EventHandler
	protected void onEvent(ModuleCanceledEvent evt) {
		Object theObject = evt.getModule().getDelegateObject();
		if (theObject instanceof Unrecordable) return;
		// TODO - this could be fraught with multithreaded issues
		if ((classToNotRecord != null) && (classToNotRecord.isInstance(theObject))){
			classToNotRecord = null;
			return;
		}
		if (theObject instanceof Command) {
			Display<?> display = displayService.getActiveDisplay();
			// FIXME HACK only datasets and imagedisplays supported right now
			if (!(display instanceof ImageDisplay)) return;
			// remove last undo point
			findHistory(display).removeNewestUndo();
		}
	}
	
	@EventHandler
	protected void onEvent(ModuleFinishedEvent evt) {
		Object theObject = evt.getModule().getDelegateObject();
		if (theObject instanceof Unrecordable) return;
		// TODO - this could be fraught with multithreaded issues
		if ((classToNotRecord != null) && (classToNotRecord.isInstance(theObject))){
			classToNotRecord = null;
			return;
		}
		if (theObject instanceof Command) {
			Display<?> display = displayService.getActiveDisplay();
			// FIXME HACK only datasets and imagedisplays supported right now
			if (!(display instanceof ImageDisplay)) return;
			Dataset dataset = imageDisplayService.getActiveDataset((ImageDisplay)display);
			if (dataset == null) return;
			if (theObject instanceof InvertableCommand) {
				InvertableCommand command = (InvertableCommand) theObject;
				findHistory(display).addUndo(command.getInverseCommand(), command.getInverseInputMap());
			}
			Class<? extends Command> theClass =
					(Class<? extends Command>) theObject.getClass();
			findHistory(display).addRedo(theClass, evt.getModule().getInputs());
		}
		
	}

	// NOTE - what if you use ImageCalc to add two images (so both displays stored
	// as inputs to plugin in undo/redo stack. Then someone deletes one of the
	// displays. Then back to display that may have been a target of the calc.
	// Then undo. Should crash.
	// On a related topic do we store an undo operation on a Display<?>? Or Object?
	// How about a set of inputs like ImageCalc. Then cleanup all undo histories
	// that refer to deleted display?????
	
	@EventHandler
	protected void onEvent(DisplayDeletedEvent evt) {
		History history = histories.get(evt.getObject());
		if (history == null) return;
		history.clear();
		histories.remove(history);
	}
	
	// -- private helpers --

	private History findHistory(Display<?> disp) {
		History h = histories.get(disp);
		if (h == null) {
			h = new History();
			histories.put(disp, h);
		}
		return h;
	}
	
	private class History {
		private LinkedList<Class<? extends Command>> undoableCommands;
		private LinkedList<Class<? extends Command>> redoableCommands;
		private LinkedList<Map<String,Object>> undoableInputs;
		private LinkedList<Map<String,Object>> redoableInputs;
		private int undoPos;
		private int redoPos;

		History() {
			undoableCommands = new LinkedList<Class<? extends Command>>();
			redoableCommands = new LinkedList<Class<? extends Command>>();
			undoableInputs = new LinkedList<Map<String,Object>>();
			redoableInputs = new LinkedList<Map<String,Object>>();
			undoPos = -1;
			redoPos = -1;
		}
		
		void doUndo() {
			if ((undoPos < 0) || (undoPos >= undoableCommands.size())) return;
			Class<? extends Command> command = undoableCommands.get(undoPos);
			Map<String,Object> input = undoableInputs.get(undoPos);
			undoPos--;
			redoPos--;
			classToNotRecord = command;
			commandService.run(command, input);
		}
		
		void doRedo() {
			if ((redoPos < 0) || (redoPos >= redoableCommands.size())) return;
			Class<? extends Command> command = redoableCommands.get(redoPos);
			Map<String,Object> input = redoableInputs.get(redoPos);
			System.out.println("About to redo:");
			System.out.println("  command = "+command.getName());
			for (String key : input.keySet()) {
				System.out.println("  input: "+key+" : "+input.get(key));
			}
			undoPos++;
			redoPos++;
			classToNotRecord = command;
			commandService.run(command, input);
		}
		
		void clear() {
			undoableCommands.clear();
			redoableCommands.clear();
			undoableInputs.clear();
			redoableInputs.clear();
			undoPos = -1;
			redoPos = -1;
		}
		
		// TODO - if not at end clear out some list entries above
		
		void addUndo(Class<? extends Command> command, Map<String,Object> inputs) {
			undoableCommands.add(command);
			undoableInputs.add(inputs);
			undoPos++;
			if (undoPos > MAX_STEPS) removeOldestUndo();
		}
		
		void addRedo(Class<? extends Command> command, Map<String,Object> inputs) {
			redoableCommands.add(command);
			redoableInputs.add(inputs);
			redoPos++;
			if (redoPos > MAX_STEPS) removeOldestRedo();
		}
		
		void removeNewestUndo() {
			undoableCommands.removeLast();
			undoableInputs.removeLast();
			undoPos--;
		}

		void removeNewestRedo() {
			redoableCommands.removeLast();
			redoableInputs.removeLast();
			redoPos--;
		}
		
		void removeOldestUndo() {
			undoableCommands.removeFirst();
			undoableInputs.removeFirst();
			undoPos--;
		}

		void removeOldestRedo() {
			redoableCommands.removeFirst();
			redoableInputs.removeFirst();
			redoPos--;
		}

	}
}