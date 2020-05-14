package uk.co.edstow.cpacgen.pairgen;

import uk.co.edstow.cpacgen.Atom;
import uk.co.edstow.cpacgen.Goal;
import uk.co.edstow.cpacgen.Transformation;

public class Distance {
    final int x, y, z;

    public Distance(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Distance(Atom a, Atom b){
        this.x = b.x-a.x;
        this.y = b.y-a.y;
        this.z = b.z-a.z;
    }

    public Distance(Goal.AveragePosition position){
        this.x = (int) Math.round(position.x);
        this.y = (int) Math.round(position.y);
        this.z = (int) Math.round(position.z);
    }

    public Distance(Transformation.Direction dir, int length){
        this.x = -dir.x * length;
        this.y = -dir.y * length;
        this.z = 0;
    }

    public boolean isZero(){
        return x==0 && y==0 && z==0;
    }

    public int manhattan() {
        return Math.abs(x)+Math.abs(y)+Math.abs(z);
    }

    @Override
    public String toString() {
        return "D:("+x+","+y+","+z+")";
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(x) ^ Integer.hashCode(y) ^ Integer.hashCode(z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Distance && ((Distance) o).x == x && ((Distance) o).y == y && ((Distance) o).z == z;
    }

    public Distance inverse(){
        return new Distance(-x, -y, -z);
    }

    public Goal translate(Goal goal){
        Goal.Factory factory = new Goal.Factory();
        for (Atom a: goal) {
            factory.add(a.moved(x, y, z));
        }
        return factory.get();
    }

    public Transformation.Direction majorDirection() {
        int absX = Math.abs(x);
        int absY = Math.abs(y);
        int absZ = Math.abs(z);

        if (isZero()){
            return null;
        }
        if(absX >= absY && absX >= absZ){
            return x>0? Transformation.Direction.E : Transformation.Direction.W;
        }
        if(absY >= absX && absY >= absZ){
            return y>0? Transformation.Direction.N : Transformation.Direction.S;
        }
        if(absZ >= absX && absZ >= absY){
            throw new UnsupportedOperationException("Movement in Z not supported");
        }
        return null;
    }

    public Distance then(Transformation.Direction direction) {
        return new Distance(x-direction.x, y-direction.y, z);
    }

    public boolean same(Atom a, Atom b) {
        if(this.x != b.x-a.x){
            return false;
        }
        if(this.y != b.y-a.y){
            return false;
        }
        if(this.z != b.z-a.z){
            return false;
        }
        return true;
    }
}
