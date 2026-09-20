package com.group_finity.mascot.action;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.animation.Animation;
import com.group_finity.mascot.script.VariableException;
import com.group_finity.mascot.script.VariableMap;

import java.util.List;
import java.util.ResourceBundle;

/**
 * Nigel's fall: vanilla falling physics, plus a running record of how far
 * he has dropped. The landing Select routes high falls to the splat show;
 * small ones bounce like always.
 */
public class NigelFall extends Fall {
    public NigelFall(ResourceBundle schema, final List<Animation> animations, final VariableMap context) {
        super(schema, animations, context);
    }

    private int startY;

    @Override
    public void init(final Mascot mascot) throws VariableException {
        super.init(mascot);
        startY = mascot.getAnchor().y;
        mascot.setLastFallHeight(0);
    }

    @Override
    protected void tick() throws LostGroundException, VariableException {
        super.tick();
        getMascot().setLastFallHeight(Math.max(0, getMascot().getAnchor().y - startY));
    }
}
