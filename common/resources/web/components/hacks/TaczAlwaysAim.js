import { createSimpleComponent } from '/components/SimpleModule.js'

export function createComponent(template) {
    return createSimpleComponent('/api/tacz-always-aim', template, {
        components: ['SwitchCheckbox']
    });
}
