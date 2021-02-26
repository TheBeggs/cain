package uk.co.edstow.cain;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import uk.co.edstow.cain.goals.Kernel3DGoal;
import uk.co.edstow.cain.pairgen.PairGenFactory;
import uk.co.edstow.cain.regAlloc.LinearScanRegisterAllocator;
import uk.co.edstow.cain.regAlloc.RegisterAllocator;
import uk.co.edstow.cain.scamp5.analogue.Scamp5AnalogueFileRun;
import uk.co.edstow.cain.scamp5.digital.Scamp5DigitalFileRun;
import uk.co.edstow.cain.scamp5.emulator.Scamp5Verifier;
import uk.co.edstow.cain.structures.Goal;
import uk.co.edstow.cain.structures.GoalBag;
import uk.co.edstow.cain.structures.Plan;
import uk.co.edstow.cain.traversal.*;


import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class FileRun<G extends Goal<G>, T extends Transformation<?>> {
    private static int verbose;

    public static FileRun<?,?> loadFromJson(String path) {

        JSONObject config = fromJson(path);
        verbose = config.has("verbose") ? config.getInt("verbose") : 10;
        printLn("Json config read from   : '" + path + "'");
        printLn("Name                    : " + config.getString("name"));

        String goalSystem = config.getString("goalSystem");
        switch (goalSystem) {
            case "Kernel3D":
                return Kernel3DFileRun.getKernel3DFileRun(config);
            default:
                throw new IllegalArgumentException("GoalSystem Unknown");
        }
    }


    public class Result {
        public final Plan<G,T> plan;
        public final long nodesExpanded;
        public final int cost;
        public final int depth;
        public final int[] circuitDepths;
        public final long time;
        public final String code;
        public final List<G> initialGoals;
        public final List<G> finalGoals;
        public final String verificationOutput;

        public Result(Plan<G,T> plan, long nodesExpanded, long time, String code, String verf) {
            this.plan = plan;
            this.nodesExpanded = nodesExpanded;
            this.time = time;
            this.cost = reverseSearch.costFunction.apply(plan);
            this.depth = plan.depth();
            this.circuitDepths = plan.circuitDepths();
            this.code = code;
            this.initialGoals = reverseSearch.getInitialGoals();
            this.finalGoals = reverseSearch.getFinalGoals();
            this.verificationOutput = verf;

        }
    }


//    protected List<RegisterAllocator.Register> outputRegisters;

    protected final JSONObject config;
    protected final List<G> finalGoals;
    protected final List<G> initialGoals;
    protected final RegisterAllocator<G, T> registerAllocator;
    protected final PairGenFactory<G,T> pairGenFactory;
    protected final ReverseSearch<G,T> reverseSearch;
    protected final Verifier<G> verifier;


    public FileRun(JSONObject config) {
        this.config = config;

        printLn("making finalGoals");
        finalGoals = makeFinalGoals();
        printLn("making InitialGoals");
        initialGoals = makeInitialGoals();
        printLn("making RegisterAllocator");
        registerAllocator = makeRegisterAllocator();
        printLn("making RunConfig");
        ReverseSearch.RunConfig<G,T> runConfig = makeRunConfig(config.getJSONObject("runConfig"));
        printLn("making PairGenFactory");
        pairGenFactory = makePairGenFactory();


        printLn("Initialising Reverse Search:");
        reverseSearch = new ReverseSearch<>(initialGoals, finalGoals, pairGenFactory, runConfig, registerAllocator);

        verifier = makeVerifier();
    }

    protected abstract List<G> makeFinalGoals();

    protected abstract List<G> makeInitialGoals();

    protected abstract RegisterAllocator<G, T> makeRegisterAllocator();

    protected abstract PairGenFactory<G,T> makePairGenFactory();

    protected abstract Verifier<G> makeVerifier();

//    protected void configureFinalGoals(List<G> finalGoals, int approximationDepth) {
//
//        RegisterAllocator.Register[] availableRegisters = getRegisterArray(config.getJSONArray("availableRegisters"));
//        outputRegisters = new ArrayList<>();
//        for (int i = 0; i < finalGoals.size(); i++) {
//            outputRegisters.add(availableRegisters[i]);
//        }
//        this.approximationDepth = approximationDepth;
//        printLn("\tgoals:");
//        printLn(GoalBag.toGoalsString(finalGoals, false, false, true, true));
//        printLn("Depth                   : " + this.approximationDepth);
//        printLn("Output Registers        : " + outputRegisters.toString());
//
//    }

    protected ReverseSearch.RunConfig<G,T> makeRunConfig(JSONObject json) {
        printLn("\tMaking RunConfig:");
        ReverseSearch.RunConfig<G,T> runConfig = new ReverseSearch.RunConfig<>();
        //         Name                    :
        printLn("Search Time                 : " + json.getInt("searchTime"));
        runConfig.setSearchTime(json.getInt("searchTime"));
        printLn("Time Out                    : " + json.getBoolean("timeOut"));
        runConfig.setTimeOut(json.getBoolean("timeOut"));
        if (json.has("maxNodes")) {
            printLn("Max Nodes                   : " + json.getInt("maxNodes"));
            runConfig.setMaxNodes(json.getInt("maxNodes"));
        }
        printLn("Workers                     : " + json.getInt("workers"));
        runConfig.setWorkers(json.getInt("workers"));

        Function<Plan<G,T>, Integer> costFunction;
        switch (json.getString("costFunction")) {
            default:
                throw new IllegalArgumentException("Unknown Cost Function");
            case "CircuitDepth":
                printLn("Cost Function               : Maximum Circuit Depth");
                costFunction = Plan::maxCircuitDepth;
                break;
            case "PlanLength":
                printLn("Cost Function               : Plan Length");
                costFunction = Plan::depth;
                break;
            case "InstructionCost":
                printLn("Cost Function               : Total Instruction Cost");
                costFunction = plan -> (int) plan.totalInstructionCost();
                break;
            case "CircuitDepthThenLength":
                printLn("Cost Function               : Maximum CircuitDepth, then Plan Length");
                final int maxDepth1 = json.getInt("initialMaxDepth");
                costFunction = p -> p.maxCircuitDepth() * maxDepth1 + p.depth();
                break;
            case "LengthThenCircuitDepth":
                printLn("Cost Function               : Plan Length, then Maximum CircuitDepth");
                final int maxDepth2 = json.getInt("initialMaxDepth");
                costFunction = p -> p.depth() * maxDepth2 + p.maxCircuitDepth();
        }
        runConfig.setCostFunction(costFunction);

        switch (json.getString("traversalAlgorithm")) {
            default:
                throw new IllegalArgumentException("Unknown Traversal Algorithm");
            case "SOT":
                printLn("Traversal Algorithm         : Stow-Optimised-Traversal");
                runConfig.setTraversalAlgorithm(SOT.SOTFactory());
                break;
            case "SOTN":
                int n = json.getInt("SOTN");
                printLn("Traversal Algorithm         : Stow-Optimised-Traversal-N:" + n);
                runConfig.setTraversalAlgorithm(SOTN.SOTNFactory(n));
                break;
            case "BFS":
                printLn("Traversal Algorithm         : Breadth-First-Search");
                runConfig.setTraversalAlgorithm(BFS.BFSFactory());
                break;
            case "DFS":
                printLn("Traversal Algorithm         : Depth-First-Search");
                runConfig.setTraversalAlgorithm(DFS.DFSFactory());
                break;
            case "HOS":
                printLn("Traversal Algorithm         : Heir-Ordered-Search");
                runConfig.setTraversalAlgorithm(HOS.HOSFactory());
                break;
            case "RT":
                printLn("Traversal Algorithm         : Random-Traversal");
                runConfig.setTraversalAlgorithm(RT.RTFactory());
                break;
            case "BestFirstSearch":
                printLn("Traversal Algorithm         : Best-First-Search");
                runConfig.setTraversalAlgorithm(BestFirstSearch.BestFirstSearchFactory(ws -> (double) costFunction.apply(ws.currentPlan)));
                break;
        }

        printLn("Live Counter                : " + json.getBoolean("liveCounter"));
        runConfig.setLiveCounter(json.getBoolean("liveCounter"));

        printLn("Quiet                       : " + json.getBoolean("quiet"));
        runConfig.setQuiet(json.getBoolean("quiet"));

        printLn("Print Plans Live            : " + json.getInt("livePrintPlans"));
        runConfig.setLivePrintPlans(json.getInt("livePrintPlans"));

        printLn("Initial Max Depth           : " + json.getInt("initialMaxDepth"));
        runConfig.setInitialMaxDepth(json.getInt("initialMaxDepth"));

        printLn("Forced Depth Reduction      : " + json.getInt("forcedDepthReduction"));
        runConfig.setForcedDepthReduction(json.getInt("forcedDepthReduction"));

        printLn("Initial Max Cost            : " + json.getInt("initialMaxCost"));
        runConfig.setInitialMaxCost(json.getInt("initialMaxCost"));

        printLn("Forced Cost Reduction       : " + json.getInt("forcedCostReduction"));
        runConfig.setForcedCostReduction(json.getInt("forcedCostReduction"));

        printLn("Allowable Atoms Coefficient : " + json.getInt("allowableAtomsCoefficient"));
        runConfig.setAllowableSumTotalCoefficient(json.getInt("allowableAtomsCoefficient"));

        printLn("AtomGoal Reductions Per Step    : " + json.getInt("goalReductionsPerStep"));
        runConfig.setGoalReductionsPerStep(json.getInt("goalReductionsPerStep"));

        printLn("AtomGoal Reductions Tolerance   : " + json.getInt("goalReductionsTolerance"));
        runConfig.setGoalReductionsTolerance(json.getInt("goalReductionsTolerance"));

        return runConfig;
    }


    public void run() {
        reverseSearch.search();
        reverseSearch.printStats();
    }


    public String getBest() {
        List<Plan<G,T>> plans = reverseSearch.getPlans();
        if (plans.isEmpty()) {
            printLnCritial("No Plans Found!");
            return null;
        }
        double min = Double.MAX_VALUE;
        int iMin = 0;
        for (int i = 0; i < plans.size(); i++) {
            Plan<G,T> pl = plans.get(i);
            double c = reverseSearch.costFunction.apply(pl);
            if (c < min) {
                iMin = i;
                min = c;
            }
        }
        printLn("Best Plan: ");
        Plan<G,T> p = plans.get(iMin);
        printLn(p.toString());
        printLn(p.toGoalsString());

        printLnImportant("length: " + p.depth() + " Cost: " + reverseSearch.costFunction.apply(p));
        printLnImportant("CircuitDepths:" + Arrays.toString(p.circuitDepths()));
        RegisterAllocator.Mapping<G> mapping = registerAllocator.solve(p);
        String code = p.produceCode(mapping);
        printLnCritial(code);
        String verf = verifier.verify(code, initialGoals, finalGoals, p, registerAllocator);
        if (verf == null) {
            printLnCritial("Plan Was Faulty!");
            return null;
        } else {
            printLn(verf);
        }
        return code;
    }

    public List<Result> getResults() {
        List<Result> results = new ArrayList<>();
        List<Plan<G,T>> plans = reverseSearch.getPlans();
        for (int i = 0; i < plans.size(); i++) {
            Plan<G,T> plan = plans.get(i);
            RegisterAllocator.Mapping<G> mapping = registerAllocator.solve(plan);
            String code = plan.produceCode(mapping);
            String verf = verifier.verify(code, initialGoals, finalGoals, plan, registerAllocator);
            results.add(new Result(
                    plans.get(i),
                    reverseSearch.getPlanNodesExplored().get(i),
                    reverseSearch.getPlanTimes().get(i),
                    code,
                    verf));
        }
        return results;
    }

    public static abstract class Kernel3DFileRun<G extends Kernel3DGoal<G>, T extends Transformation<?>> extends FileRun<G,T> {
        protected int approximationDepth;

        public static Kernel3DFileRun<?,?> getKernel3DFileRun(JSONObject config) {
            JSONObject json = config.getJSONObject("pairGen");
            switch (json.getString("name")) {
                default:
                    throw new IllegalArgumentException("Unknown PairGen Factory " + json.getString("name"));
                case "Scamp5AnalogueAtomGoal":
                    return new Scamp5AnalogueFileRun.AtomGoalFileRun(config);
                case "Scamp5AnalogueArrayGoal":
                    return new Scamp5AnalogueFileRun.ArrayGoalFileRun(config);
                case "Scamp5DigitalAtomGoal":
                    return new Scamp5DigitalFileRun.AtomGoalFileRun(config);
                case "Scamp5DigitalArrayGoal":
                    return new Scamp5DigitalFileRun.ArrayGoalFileRun(config);
            }
        }

        public Kernel3DFileRun(JSONObject config) {
            super(config);
        }


        protected abstract Kernel3DGoal.Kernel3DGoalFactory<G> getGoalFactory();

        protected List<? extends RegisterAllocator.Register> getOutputRegisters() {
            if (config.has("filter")) {
                JSONObject filter = config.getJSONObject("filter");
                return filter.keySet().stream().map(RegisterAllocator.Register::new).collect(Collectors.toList());
            } else {
                return new ArrayList<>();
            }
        }

        @Override
        protected List<G> makeFinalGoals() {

            int maxApproximationDepth = config.getInt("maxApproximationDepth");
            printLn("Max Approximation Depth : " + maxApproximationDepth);
            double maxApproximationError = config.getDouble("maxApproximationError");
            printLn("Max Approximation Error : " + maxApproximationError);

            Approximater<G> goalAprox = new Approximater<G>(maxApproximationDepth, maxApproximationError);


            boolean threeDimentional = config.getBoolean("3d");
            printLn("Three Dimensional       : " + threeDimentional);
            JSONObject filter = config.getJSONObject("filter");
            printLn("Kernels                 : " + filter.length());
            Iterator<String> filters = filter.keySet().stream().sorted().iterator();
            while (filters.hasNext()) {
                String reg = filters.next();
                Object o = filter.get(reg);
                if (o instanceof JSONArray) {
                    addGoal(goalAprox, (JSONArray) o, threeDimentional, 1);
                } else {
                    double scale = 1;
                    if (filter.getJSONObject(reg).has("scale")) {
                        scale = filter.getJSONObject(reg).getDouble("scale");
                    }
                    if (filter.getJSONObject(reg).has("depth")) {
                        scale *= Math.pow(2, filter.getJSONObject(reg).getDouble("depth"));
                    }
                    addGoal(goalAprox, filter.getJSONObject(reg).getJSONArray("array"), threeDimentional, scale);
                }
            }

            List<G> finalGoals = goalAprox.solve(this::getGoalFactory);
            this.approximationDepth = goalAprox.getDepth();
            printLn("Output Registers        : " +getOutputRegisters());
            printLn("\tApproximated goals:");
            printLn(GoalBag.toGoalsString(finalGoals, false, false, true, true));
            printLn("");
            printLn("Approximation Depth     : " + goalAprox.getDepth());
            printLn("Approximation Error     : " + goalAprox.getError());
            return finalGoals;
        }

        @Override
        protected List<G> makeInitialGoals() {
            int[] divisions = getInitDivisions();
            List<G> initialGoals = new ArrayList<>();
            for (int i = 0; i < divisions.length; i++) {
                int division = divisions[i];
                initialGoals.add(getGoalFactory().add(0, 0, i, 1 << division).get());
            }
            return initialGoals;
        }

        protected int[] getInitDivisions(){
            int[] divisions = new int[config.getJSONObject("registerAllocator").getJSONArray("initialRegisters").length()];
            Arrays.fill(divisions, this.approximationDepth);
            return divisions;
        };

        protected abstract List<? extends RegisterAllocator.Register> getRegisterArray(JSONArray availableRegisters);
        protected abstract List<? extends RegisterAllocator.Register> getInputRegisters();


        protected Verifier<G> makeVerifier() {
            String verf = config.getString("verifier");
            switch (verf) {
                case "Scamp5Emulator":
                    Verifier<G> v = new Scamp5Verifier<>();
                    v.verbose(verbose);
                    return v;
                case "None":
                    return Verifier.SkipVerify();
                default:
                    throw new IllegalArgumentException("Verifier Unknown");
            }
        }

        private static <G extends Kernel3DGoal<G>> void addGoal(Approximater<G> goalAprox, JSONArray jsonArray, boolean threeDimentional, double scale) {
            int xMax = 0;
            int yMax = jsonArray.length();
            int zMax = 0;
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray row = jsonArray.getJSONArray(i);
                xMax = Math.max(xMax, row.length());
                if (threeDimentional) {
                    for (int j = 0; j < row.length(); j++) {
                        zMax = Math.max(zMax, row.getJSONArray(j).length());
                    }
                }
            }

            int xOffset = xMax / 2;
            int yOffset = yMax / 2;
            int zOffset = zMax / 2;

            goalAprox.newGoal();
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONArray row = jsonArray.getJSONArray(i);
                for (int j = 0; j < row.length(); j++) {
                    Object o = row.get(j);
                    if (o instanceof JSONArray) {
                        JSONArray array = (JSONArray) o;
                        for (int k = 0; k < array.length(); k++) {
                            double coefficant = array.getDouble(k);
                            int z = k;
                            goalAprox.put(j - xOffset, -(i - yOffset), z - zOffset, coefficant * scale);
                        }
                    } else if (!threeDimentional) {
                        double coefficant = row.getDouble(j);
                        goalAprox.put(j - xOffset, -(i - yOffset), 0, coefficant * scale);
                    } else {
                        throw new IllegalArgumentException("Cannot parse kernel: " + jsonArray);
                    }
                }
            }
        }

    }

    public static abstract class Kernel3DStdTransFileRun<G extends Kernel3DGoal<G>, T extends StandardTransformation> extends Kernel3DFileRun<G,T>{
        public Kernel3DStdTransFileRun(JSONObject config) {
            super(config);
        }

        protected List<? extends RegisterAllocator.Register> getRegisterArray(JSONArray availableRegisters) {
            ArrayList<RegisterAllocator.Register> out = new ArrayList<>(availableRegisters.length());
            for (int i = 0; i < availableRegisters.length(); i++) {
                out.add(new RegisterAllocator.Register(availableRegisters.getString(i)));
            }
            return out;
        }
        protected List<? extends RegisterAllocator.Register> getInputRegisters(){
            return getRegisterArray(config.getJSONObject("registerAllocator").getJSONArray("initialRegisters"));
        }
        protected RegisterAllocator<G, T> makeRegisterAllocator() {
            JSONObject regAllocConf = config.getJSONObject("registerAllocator");
            switch (regAllocConf.getString("name")){
                case "linearScan":
                    printLn("\tMaking Linear Scan Register Allocator:");
                    List<RegisterAllocator.Register> availableRegisters = new ArrayList<>();
                {
                    JSONArray regArray =config.getJSONObject("registerAllocator").getJSONArray("availableRegisters");
                    for (int i = 0; i < regArray.length(); i++) {
                        availableRegisters.add(new RegisterAllocator.Register(regArray.getString(i)));
                    }
                }
                printLn("Available registers  : " + availableRegisters.toString());

                List<RegisterAllocator.Register> available = new ArrayList<>(getOutputRegisters());
                for (RegisterAllocator.Register availableRegister : availableRegisters) {
                    if (!available.contains(availableRegister)) {
                        available.add(availableRegister);
                    }
                }
                List<RegisterAllocator.Register> initRegisters = new ArrayList<>(getInputRegisters());
                printLn("Initial registers    : " + initRegisters.toString());
                return new LinearScanRegisterAllocator<>(initRegisters, initialGoals, available);
                default:
                    throw new IllegalArgumentException("Register Allocator Unknown");
            }

        }

    };


    public String getAvailableRegisters() {
        return registerAllocator.getAvailableRegistersArray().toString();
    }

    public String getTraversalAlgorithm() {
        return config.getJSONObject("runConfig").getString("traversalAlgorithm");
    }

    public String getCostFunction() {
        return config.getJSONObject("runConfig").getString("costFunction");
    }

    public int getForcedDepthReduction() {
        return config.getJSONObject("runConfig").getInt("forcedDepthReduction");
    }

    public int getForcedCostReduction() {
        return config.getJSONObject("runConfig").getInt("forcedCostReduction");
    }


    public static JSONObject fromJson(String path) {
        return fromJson(path, false);
    }

    public static JSONObject fromJson(String path, boolean setVerbose) {

        InputStream in = null;
        JSONTokener tokeniser = null;

        try {
            in = new FileInputStream(path);
            tokeniser = new JSONTokener(in);
        } catch (FileNotFoundException e) {
            try {
                tokeniser = new JSONTokener(path);
            } catch (JSONException ex) {
                System.out.println("Cannot find file, or interpret as Json directly!");
                e.printStackTrace();
                ex.printStackTrace();
                System.exit(-1);
            }
        }
        JSONObject config = new JSONObject(tokeniser);
        int verbose = config.has("verbose") ? config.getInt("verbose") : 10;
        if (verbose > 5 && in != null) {
            System.out.println("Json config read from   : '" + path + "'");
        }
        if (verbose > 5 && in == null) {
            System.out.println("Json config read directly");
        }
        if (verbose > 10 && in == null) {
            System.out.println("Json config:\n" + path + "\n");
        }
        if (setVerbose) {
            FileRun.verbose = verbose;
            if (verbose > 5) {
                System.out.println("Verbose set to " + verbose);
            }
        }


        return config;
    }


    public static void printLnVerbose(String s) {
        if (verbose > 10) System.out.println(s);
    }

    public static void printLnVerbose(String s, Object... args) {
        if (verbose > 10) System.out.printf((s) + "%n", args);
    }

    public static void printLn(String s) {
        if (verbose > 5) System.out.println(s);
    }

    public static void printLn(String s, Object... args) {
        if (verbose > 5) System.out.printf((s) + "%n", args);
    }

    public static void printLnImportant(String s) {
        if (verbose > 0) System.out.println(s);
    }

    public static void printLnImportant(String s, Object... args) {
        if (verbose > 0) System.out.printf((s) + "%n", args);
    }

    public static void printLnCritial(String s) {
        if (verbose >= 0) System.out.println(s);
    }

    public static void printLnCritial(String s, Object... args) {
        if (verbose >= 0) System.out.printf((s) + "%n", args);
    }
}
