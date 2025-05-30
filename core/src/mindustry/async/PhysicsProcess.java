package mindustry.async;

import arc.math.*;
import arc.math.geom.*;
import arc.math.geom.QuadTree.*;
import arc.struct.*;
import mindustry.*;
import mindustry.async.PhysicsProcess.PhysicsWorld.*;
import mindustry.entities.*;
import mindustry.gen.*;

public class PhysicsProcess implements AsyncProcess{
    public static final int
    layers = 4,
    layerGround = 0,
    layerLegs = 1,
    layerFlying = 2,
    layerUnderwater = 3;

    private PhysicsWorld physics;
    private Seq<PhysicRef> refs = new Seq<>(false);
    //currently only enabled for units
    private EntityGroup<Unit> group = Groups.unit;

    @Override
    public void begin(){
        if(physics == null) return;
        boolean local = !Vars.net.client();

        //remove stale entities
        refs.removeAll(ref -> {
            if(!ref.entity.isAdded()){
                physics.remove(ref.body);
                ref.entity.physref(null);
                return true;
            }
            return false;
        });

        //find Units without bodies and assign them
        for(Unit entity : group){
            if(entity == null || entity.type == null || !entity.type.physics) continue;

            if(entity.physref == null){
                PhysicsBody body = new PhysicsBody();
                body.x = entity.x;
                body.y = entity.y;
                body.mass = entity.mass();
                body.radius = entity.hitSize * Vars.unitCollisionRadiusScale;

                PhysicRef ref = new PhysicRef(entity, body);
                refs.add(ref);

                entity.physref = ref;

                physics.add(body);
            }

            //save last position
            PhysicRef ref = entity.physref;

            ref.body.layer = entity.collisionLayer();
            ref.x = entity.x;
            ref.y = entity.y;
            ref.body.local = local || entity.isLocal();
        }
    }

    @Override
    public void process(){
        if(physics == null) return;

        //get last position vectors before step
        for(PhysicRef ref : refs){
            //force set target position
            ref.body.x = ref.x;
            ref.body.y = ref.y;
        }

        physics.update();
    }

    @Override
    public void end(){
        if(physics == null) return;

        //move entities
        for(PhysicRef ref : refs){
            Physicsc entity = ref.entity;

            //move by delta
            entity.move(ref.body.x - ref.x, ref.body.y - ref.y);
        }
    }

    @Override
    public void reset(){
        if(physics != null){
            refs.clear();
            physics = null;
        }
    }

    @Override
    public void init(){
        reset();

        physics = new PhysicsWorld(Vars.world.getQuadBounds(new Rect()));
    }

    public static class PhysicRef{
        public Physicsc entity;
        public PhysicsBody body;
        public float x, y;

        public PhysicRef(Physicsc entity, PhysicsBody body){
            this.entity = entity;
            this.body = body;
        }
    }

    //world for simulating physics in a different thread
    public static class PhysicsWorld{
        //how much to soften movement by
        private static final float scl = 1.25f;

        private final QuadTree<PhysicsBody>[] trees = new QuadTree[layers];
        private final Seq<PhysicsBody> bodies = new Seq<>(false, 16, PhysicsBody.class);
        private final Seq<PhysicsBody> seq = new Seq<>(PhysicsBody.class);
        private final Rect rect = new Rect();
        private final Vec2 vec = new Vec2();

        public PhysicsWorld(Rect bounds){
            for(int i = 0; i < layers; i++){
                trees[i] = new QuadTree<>(new Rect(bounds));
            }
        }

        public void add(PhysicsBody body){
            bodies.add(body);
        }

        public void remove(PhysicsBody body){
            bodies.remove(body);
        }

        public void update(){
            for(int i = 0; i < layers; i++){
                trees[i].clear();
            }

            var bodyItems = bodies.items;
            int bodySize = bodies.size;

            for(int i = 0; i < bodySize; i++){
                PhysicsBody body = bodyItems[i];
                if(body.layer < 0) continue;
                body.collided = false;
                trees[body.layer].insert(body);
            }

            for(int i = 0; i < bodySize; i++){
                PhysicsBody body = bodyItems[i];
                //for clients, the only body that collides is the local one; all other physics simulations are handled by the server.
                if(!body.local || body.layer < 0) continue;

                body.hitbox(rect);

                seq.size = 0;
                trees[body.layer].intersect(rect, seq);
                int size = seq.size;
                var items = seq.items;

                for(int j = 0; j < size; j++){
                    PhysicsBody other = items[j];

                    if(other == body || other.collided) continue;

                    float rs = body.radius + other.radius;
                    float dst = Mathf.dst(body.x, body.y, other.x, other.y);

                    if(dst < rs){
                        vec.set(body.x - other.x, body.y - other.y).setLength(rs - dst);
                        float ms = body.mass + other.mass;
                        float m1 = other.mass / ms, m2 = body.mass / ms;

                        //first body is always local due to guard check above
                        body.x += vec.x * m1 / scl;
                        body.y += vec.y * m1 / scl;

                        if(other.local){
                            other.x -= vec.x * m2 / scl;
                            other.y -= vec.y * m2 / scl;
                        }
                    }
                }
                body.collided = true;
            }
        }

        public static class PhysicsBody implements QuadTreeObject{
            public float x, y, radius, mass;
            public int layer = 0;
            public boolean collided = false, local = true;

            @Override
            public void hitbox(Rect out){
                out.setCentered(x, y, radius * 2, radius * 2);
            }
        }
    }
}
