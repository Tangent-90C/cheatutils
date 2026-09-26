import { createSimpleComponent } from '/components/SimpleModule.js'
import * as http from '/http.js'

const entityInfoPromise = http.get('/api/entity-info').then(entitiesList => {
    const entitiesMap = {};
    entitiesList.forEach(e => entitiesMap[e.clazz] = e);
    return { entitiesList: entitiesList, entitiesMap: entitiesMap };
});

export function createComponent(template) {
    const args = createSimpleComponent('/api/aim-assist', template, {
        components: ['CodeBlock', 'Radio'],
        css: import.meta.url
    });

    const created = args.created;
    args.created = function () {
        entityInfoPromise.then(info => {
            this.entitiesList = info.entitiesList;
            this.entitiesMap = info.entitiesMap;
        });
        created.call(this);
    };
    args.data = function () {
        return {
            config: null,
            entitiesList: null,
            entitiesMap: null,
            search: ''
        };
    };
    args.methods.entityListFiltered = function () {
        if (!this.entitiesList) {
            return [];
        }
        const search = (this.search || '').toLocaleLowerCase();
        if (search == '') {
            return this.entitiesList.filter(e => !e.isInterface);
        }
        return this.entitiesList.filter(e =>
            !e.isInterface &&
            e.simpleName && e.simpleName.toLocaleLowerCase().indexOf(search) >= 0);
    };
    args.methods.toggleTargetClass = function (name) {
        const classes = this.config.targetClasses || [];
        this.config.targetClasses = classes.includes(name)
            ? classes.filter(c => c != name)
            : classes.concat([name]);
        this.update();
    };
    args.methods.clearTargetClasses = function () {
        this.config.targetClasses = [];
        this.update();
    };
    args.methods.classLabel = function (name) {
        const info = this.entitiesMap ? this.entitiesMap[name] : null;
        return info ? info.simpleName : name;
    };
    args.methods.classTooltip = function (name) {
        const info = this.entitiesMap ? this.entitiesMap[name] : null;
        return info && info.baseClasses ? info.baseClasses.map(c => c.split('.').pop()).join(' > ') : '';
    };

    return args;
}
