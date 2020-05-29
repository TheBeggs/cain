package uk.co.edstow.cpacgen.pairgen;

import uk.co.edstow.cpacgen.util.Bounds;
import uk.co.edstow.cpacgen.util.Tuple;
import uk.co.edstow.cpacgen.Atom;
import uk.co.edstow.cpacgen.Goal;
import uk.co.edstow.cpacgen.ReverseSearch;
import uk.co.edstow.cpacgen.Transformation;

import java.util.*;

public class SimplePairGenFactory implements PairGenFactory {

    private Bounds bounds;


    public static Collection<Tuple<List<Goal.Pair>, Goal>> applyAllUnaryOps(Goal goal, Goal upper){
        ArrayList<Tuple<List<Goal.Pair>, Goal>> list = new ArrayList<>();
        for (SimpleTransformation.Direction d: SimpleTransformation.Direction.values()){
            for (int i = 0; i < 4; i++){
                SimpleTransformation t = new SimpleTransformation.Move(i, d, goal);
                try {
                    Goal go = t.applyForwards();
                    if(go.same(upper)) {
                        list.add(new Tuple<>(Collections.singletonList(new Goal.Pair(upper, goal, t)), goal));
                    }
                } catch (Transformation.TransformationApplicationException ignored) {}
            }
        }
        for (int i = 0; i < 8; i++){
            SimpleTransformation t = new SimpleTransformation.Div(i, goal);
            try {
                Goal go = t.applyForwards();
                if(go.same(upper)) {
                    list.add(new Tuple<>(Collections.singletonList(new Goal.Pair(upper, goal, t)), goal));
                }
            } catch (Transformation.TransformationApplicationException ignored) {}
        }
        return list;
    }

    @Override
    public Collection<Tuple<List<Goal.Pair>, Goal>> applyAllUnaryOpForwards(List<Goal> initialGoals, int depth, Goal goal) {
        return applyAllUnaryOps(initialGoals.get(0), goal);
    }

    @Override
    public void init(ReverseSearch rs) {
        bounds = new Bounds(rs.getFinalGoals());
    }

    @Override
    public PairGen generatePairs(Goal.Bag goals, int depth) {
        return generatePairs(goals);
    }
    @SuppressWarnings("WeakerAccess")
    public PairGen generatePairs(Goal.Bag goals) {
        return new SimplePairGen(goals);
    }

    public class SimplePairGen implements PairGen {

        private final Goal.Bag goalList;
        private final List<Goal.Pair> currentList;


        public SimplePairGen(Goal.Bag goals) {
            goalList = new Goal.Bag(goals);
            goalList.setImmutable();
            currentList = new ArrayList<>();


        }

        @Override
        public final Goal.Pair next() {
            Goal.Pair p = get_next();
            while(p != null && !check(p)){
                p = get_next();
            }
            return p;

        }

        final void appendCurrentList(Goal.Pair pair){
            currentList.add(pair);
        }

        final void appendCurrentList(Collection<Goal.Pair> pairs){
            currentList.addAll(pairs);
        }


        boolean check(Goal.Pair p){
            for (Goal l: p.getLowers()) {
                for (Atom a : l) {
                    if (bounds.excludes(a)) {
                        return false;
                    }
                }
            }
            return true;

        }

        private Goal.Pair get_next() {
            if (!currentList.isEmpty()){
                return currentList.remove(0);

            } else if (!goalList.isEmpty()){
                Goal upper = goalList.remove(0);

                putTransformations(upper);

                return this.get_next();
            }
            return null;
        }

        void putTransformations(Goal upper){
            // Add
            appendCurrentList(getAddTransformations(upper));

            // Mov and Div
            appendCurrentList(getUnaryTransformations(upper));
        }

        List<Goal.Pair> getUnaryTransformations(Goal upper) {
            List<Goal.Pair> pairs = new ArrayList<>();
            Collection<Tuple<? extends Transformation, Goal>> ts = SimpleTransformation.applyAllUnaryOpBackwards(upper);
            for (Tuple<? extends Transformation, Goal> t: ts){
                pairs.add(new Goal.Pair(upper, t.getB(), t.getA()));
            }
            return pairs;
        }

        List<Goal.Pair> getAddTransformations(Goal upper) {
            List<Goal.Pair> pairs = new ArrayList<>();
            // Addition
            Collection<Goal> splits = upper.allSplitsRecursive();
            for (Goal a : splits) {
                Goal b = upper.without(a);
                if (b.isEmpty()) {
                    continue;
                }
                SimpleTransformation.Add add = new SimpleTransformation.Add(a, b);
                if (!add.applyForwards().same(upper)) throw new AssertionError();
                List<Goal> lowers = new ArrayList<>();
                lowers.add(a);
                lowers.add(b);
                pairs.add(new Goal.Pair(upper, lowers, add));
            }

            return pairs;
        }

    }
}
