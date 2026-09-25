package com.shinyhunter;

/**
 * Carries Shiny Hunter's model tint on an entity's render state, from where the state is filled in
 * (which knows the entity) to where the model colour is chosen (which only has the state).
 */
public interface TintHolder {

    int shinyhunter$getTint();

    void shinyhunter$setTint(int tint);
}
