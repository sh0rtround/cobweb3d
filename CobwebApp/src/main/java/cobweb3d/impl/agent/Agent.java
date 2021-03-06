package cobweb3d.impl.agent;

import cobweb3d.core.SimulationInternals;
import cobweb3d.core.agent.BaseAgent;
import cobweb3d.core.agent.Controller;
import cobweb3d.core.entity.Cause;
import cobweb3d.core.location.Direction;
import cobweb3d.core.location.Location;
import cobweb3d.core.location.LocationDirection;
import cobweb3d.impl.environment.Environment;
import cobweb3d.impl.params.AgentParams;
import cobweb3d.plugins.states.AgentState;

import java.util.HashMap;
import java.util.Map;

/**
 * The implementation of BaseAgent.
 * This class adds methods which interact between agent and the environment,
 * while the class BaseAgent only deals with itself, such as energy, type of its agent.
 */
public class Agent extends BaseAgent {
    public AgentParams params;
    public Map<Class<? extends AgentState>, AgentState> extraState = new HashMap<>();

    protected transient SimulationInternals simulation;
    protected transient Environment environment;

    private Controller controller;

    private long birthTick;

    public Agent(SimulationInternals simulation, int type) {
        super(type);
        this.simulation = simulation;
        birthTick = simulation.getTime();
    }

    public void setParams(AgentParams agentParams) {
        this.params = agentParams;
    }

    /**
     * Copies default parameters for this agent type to destAgent, not directly from agent.
     *
     * @param destAgent the agent to copy parameters to.
     */
    private void copyParams(Agent destAgent) {
        setParams(environment.agentParams[destAgent.getType()]);
    }

    public <T extends AgentState> void setState(Class<T> type, T value) {
        extraState.put(type, value);
    }

    public <T extends AgentState> T getState(Class<T> type) {
        @SuppressWarnings("unchecked")
        T storedState = (T) extraState.get(type);
        return storedState;
    }

    public <T extends AgentState> T removeState(Class<T> type) {
        @SuppressWarnings("unchecked")
        T removed = (T) extraState.remove(type);
        return removed;
    }

    public long getAge() {
        return simulation.getTime() - birthTick;
    }

    @Override
    public void die() {
        super.die();
        // Note: This is changeEnergy not setEnergy. That's the reason why it uses Math.min()
        changeEnergy(Math.min(0, -getEnergy()), new DeathCause());
        simulation.getAgentListener().onDeath(this);
        move(null);
    }

    @Override
    public Agent createChildAsexual(LocationDirection location) {
        Agent child = new Agent(simulation, getType());
        child.init(environment, location, this);
        // TODO: applyAgePenalty(); ?
        return child;
    }

    @Override
    public Agent createChildSexual(LocationDirection location, BaseAgent otherParent) {
        Agent child = new Agent(simulation, getType());
        child.init(environment, location, this, otherParent);
        return child;
    }

    /**
     * If the agent is too old, let it die.
     * The other updating work is left to the controller of this agent,
     * which determines the movement of this agent in this tick.
     * For information relating to the default controller, please refer to the class cobweb3d.impl.ai.random.SimpleController
     */
    @Override
    public void update() {
        if (!isAlive()) return;
        if (params.agingMode && getAge() >= params.agingLimit.getValue() || getEnergy() <= 0) {
            die();
            return;
        }
        if (controller != null) controller.controlAgent(this, simulation.getAgentListener());
    }

    /**
     * Constructor with no parent agent; creates an agent using "immaculate conception" technique.
     *
     * @param pos         spawn position
     * @param agentParams agent parameters
     */
    public void init(Environment env, LocationDirection pos, AgentParams agentParams, Controller controller) {
        environment = env;
        setParams(agentParams);
        this.controller = controller;
        simulation.getAgentListener().onSpawn(this);

        initPosition(pos);

        changeEnergy(params.initEnergy.getValue());
    }

    /**
     * Constructor with a parent; standard asexual copy.
     *
     * @param pos    spawn position
     * @param parent parent
     */
    protected void init(Environment env, LocationDirection pos, Agent parent) {
        environment = (env);
        copyParams(parent);
        controller = parent.controller.createChildAsexual();

        simulation.getAgentListener().onSpawn(this, parent);

        initPosition(pos);

        changeEnergy(params.initEnergy.getValue(), new AsexualBirthCause());
    }

    /**
     * Constructor with two parents.
     *
     * @param pos     spawn position
     * @param parent1 first parent
     * @param parent2 second parent
     */
    protected void init(Environment env, LocationDirection pos, Agent parent1, BaseAgent parent2) {
        environment = env;
        copyParams(parent1);
        if (parent2 instanceof Agent) {
            controller = parent1.controller.createChildSexual(((Agent) parent2).controller);
        } else {
            controller = parent1.controller.createChildAsexual();
        }

        simulation.getAgentListener().onSpawn(this, parent1, parent2);

        initPosition(pos);

        changeEnergy(params.initEnergy.getValue(), new SexualBirthCause());
    }

    /**
     * Initialize the agent's position
     *
     * @param pos the location where the agent stays at and the direction where the agent faces to
     */
    private void initPosition(LocationDirection pos) {
        if (pos.direction.equals(Direction.NONE))
            pos = new LocationDirection(pos, simulation.getTopology().getRandomDirection());
        move(pos);
        simulation.registerAgent(this);
    }

    /**
     * First it updates class Environment's hash table, which maps a position to the agent in that position.
     * Then it updates the position attribute of that agent, and notifies the agentListener.
     *
     * @param newPos the new position where the agent will move to
     */
    public void move(LocationDirection newPos) {
        LocationDirection oldPos = getPosition();
        if (oldPos != null) environment.setAgent(oldPos, null);
        if (newPos != null) environment.setAgent(newPos, this);
        simulation.getAgentListener().onStep(this, oldPos, newPos);
        position = newPos;
    }

    public void turnLeft() {
        position = environment.topology.getTurnLeftPosition(getPosition());//environment.topology.getTurnLeftPosition(getPosition());
    }

    public void turnRight() {
        position = environment.topology.getTurnRightPosition(getPosition());//environment.topology.getTurnRightPosition(getPosition());
    }

    public void turnUp() {
        position = environment.topology.getTurnUpPosition(getPosition());//environment.topology.getTurnUpPosition(getPosition());
    }

    public void turnDown() {
        position = environment.topology.getTurnDownPosition(getPosition());//
    }

    /**
     * It gets the expected position where the agent will head for in the next step.
     * Then the agent goes to that position, and reacts differently based on the different states of the position.
     * (E.g., the position is free, or has other agent/rock/waste)
     */
    public void step() {
        LocationDirection destPos = environment.topology.getAdjacent(getPosition());
        if (!destPos.equals(getPosition())) {
            if (canStep(destPos)) {
                onStepFreeTile(destPos);
            } else if (environment.hasAgent(destPos)) {
                onStepAgentBump(environment.getAgent(destPos));
            } else {
                // Non-free tile rock, waste, etc.
            }
        }
    }

    /**
     * It deals with the case that destPos has no other agents.
     * In this case, the agent moves to that position and decreases its energy due to its movement.
     *
     * @param destPos the destination position where the agent will move to
     */
    private void onStepFreeTile(LocationDirection destPos) {
        move(destPos);
        onStepFood(destPos);
        changeEnergy(-params.stepEnergy.getValue(), new StepForwardCause());
    }

    /**
     * It deals with the case that exist other agents at one position.
     * In this case, the agent may fight with the other agent, and loses more energy.
     * Or if the other agent is the same type with this agent, they may reproduce a child. (Unimplemented in v0.1.4)
     *
     * @param otherAgent the other agent which the current agent bumps into
     */
    private void onStepAgentBump(BaseAgent otherAgent) {
        simulation.getAgentListener().onContact(this, otherAgent);
        changeEnergy(-params.stepAgentEnergy.getValue(), new BumpAgentCause());
        if (!otherAgent.isAlive()) return;
        // If agents are the same type, try to reproduction.
        if (otherAgent instanceof Agent && otherAgent.getType() == getType()) {
            // TODO:
        }
    }

    private void onStepFood(Location l) {
        simulation.getAgentListener().onConsumeFood(this, environment.foodArray[l.x][l.y][l.z]);
        environment.removeFood(l);
    }

    private boolean canStep(Location dest) {
        return !environment.hasAgent(dest);
    }

    public static class MovementCause implements Cause {
        @Override
        public String getName() {
            return "Movement";
        }
    }

    public static class StepForwardCause extends MovementCause {
        @Override
        public String getName() {
            return "Step Forward";
        }
    }

    public static class TurnCause extends MovementCause {
        @Override
        public String getName() {
            return "Turn";
        }
    }

    public static class TurnLeftCause extends TurnCause {
        @Override
        public String getName() {
            return "Turn Left";
        }
    }

    public static class TurnRightCause extends TurnCause {
        @Override
        public String getName() {
            return "Turn Right";
        }
    }

    public static class EatCause implements Cause {
        @Override
        public String getName() {
            return "Eat";
        }
    }

    public static class EatFoodCause extends EatCause {
        @Override
        public String getName() {
            return "Eat Food";
        }
    }

    public static class EatFavoriteFoodCause extends EatCause {
        @Override
        public String getName() {
            return "Eat Favorite Food";
        }
    }

    public static class EatAgentCause extends EatCause {
        @Override
        public String getName() {
            return "Eat Agent";
        }
    }

    public static class BumpCause extends MovementCause {
        @Override
        public String getName() {
            return "Bump";
        }
    }

    public static class BumpWallCause extends BumpCause {
        @Override
        public String getName() {
            return "Bump Wall";
        }
    }

    public static class BumpAgentCause extends BumpCause {
        @Override
        public String getName() {
            return "Bump Agent";
        }
    }

    public static class ReproductionCause implements Cause {
        @Override
        public String getName() {
            return "Reproduction";
        }
    }

    public static class SexualReproductionCause extends ReproductionCause {
        @Override
        public String getName() {
            return "Sexual Reproduction";
        }
    }

    public static class AsexualReproductionCause extends ReproductionCause {
        @Override
        public String getName() {
            return "Asexual Reproduction";
        }
    }

    public static class PopulationCause implements Cause {
        @Override
        public String getName() {
            return "Agent Population";
        }
    }

    public static class DeathCause extends PopulationCause {
        @Override
        public String getName() {
            return "Death";
        }
    }

    public static class BirthCause extends PopulationCause {
        @Override
        public String getName() {
            return "Birth";
        }
    }

    public static class SexualBirthCause extends BirthCause {
        @Override
        public String getName() {
            return "Sexual Birth";
        }
    }

    public static class AsexualBirthCause extends BirthCause {
        @Override
        public String getName() {
            return "Asexual Birth";
        }
    }

    public static class CreationBirthCause extends BirthCause {
        @Override
        public String getName() {
            return "Creation"; }
    }
}

