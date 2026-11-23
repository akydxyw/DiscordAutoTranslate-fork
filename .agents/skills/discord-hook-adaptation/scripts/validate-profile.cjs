#!/usr/bin/env node
'use strict';

// Static authoring check only. APK descriptors and native behavior need separate evidence.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../../../..');
const version = process.argv[2];
if (process.argv.length !== 3 || !/^[1-9][0-9]*$/.test(version || '')
        || !Number.isSafeInteger(Number(version)) || Number(version) > 2147483647) {
    console.error('Usage: node validate-profile.cjs <positive Android versionCode>');
    process.exit(1);
}
const errors = [];
const check = (ok, where) => { if (!ok) errors.push(where); };
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = value => typeof value === 'string' && value.trim() === value
    && value.length > 0 && !value.includes('*');
const integer = value => Number.isInteger(value) && value >= -2147483648 && value <= 2147483647;
const ids = ['message.bind', 'message.render', 'message.textConversion', 'embed.bind',
    'poll.bind', 'poll.answerBind', 'poll.textSet', 'poll.accessibilitySet'];
const sameKeys = (value, names) => object(value) && Object.keys(value).length === names.length
    && names.every(name => Object.hasOwn(value, name));
function fieldPath(value, where) {
    check(Array.isArray(value) && value.length > 0 && value.every(exact), where);
}
function index(spec, key, count, where) {
    check(Number.isInteger(spec[key]) && spec[key] >= 0 && spec[key] < count, `${where}.${key}`);
}
function validate(profile) {
    check(object(profile), 'root must be an object');
    if (!object(profile)) return;
    const sections = ['schemaVersion', 'versionCode', 'requiredHooks', 'hooks', 'types',
        'typeAliases', 'accessors', 'members', 'fields', 'nodeConstructors', 'iconClick'];
    check(Object.keys(profile).every(key => sections.includes(key)), 'unsupported root section');
    check(profile.schemaVersion === 1, 'schemaVersion must be integer 1');
    check(profile.versionCode === Number(version), 'filename/versionCode mismatch');
    check(Array.isArray(profile.requiredHooks) && profile.requiredHooks.length === ids.length
        && new Set(profile.requiredHooks).size === ids.length
        && ids.every(id => profile.requiredHooks.includes(id)), 'requiredHooks must contain all eight IDs exactly once');
    check(sameKeys(profile.hooks, ids), 'hooks must contain exactly the eight supported IDs');
    for (const id of ids) {
        const spec = profile.hooks?.[id];
        if (!object(spec)) { check(false, `${id}: missing object`); continue; }
        check(!Object.hasOwn(spec, 'enabled') || spec.enabled === true, `${id}: enabled`);
        const target = spec.target;
        if (!object(target)) { check(false, `${id}: missing target`); continue; }
        check(sameKeys(target, ['class', 'name', 'static', 'parameterCount', 'returnType', 'parameterTypes']),
            `${id}: target needs only exact selector fields`);
        check(exact(target.class) && exact(target.name) && exact(target.returnType), `${id}: exact target strings`);
        check(typeof target.static === 'boolean', `${id}: static boolean`);
        const count = target.parameterCount;
        if (!Number.isInteger(count) || count < 0 || count > 255) {
            check(false, `${id}: parameterCount`); continue;
        }
        const params = target.parameterTypes;
        check(sameKeys(params, Array.from({length: count}, (_, i) => String(i)))
            && Object.values(params).every(value => exact(value) && value !== 'void'),
        `${id}: all parameterTypes required without wildcards`);
        const indexes = {
            'message.bind': ['modelArgument'],
            'message.render': ['contentArgument', 'messageIdArgument'],
            'message.textConversion': ['contentArgument', 'contextArgument', 'messageIdArgument'],
            'poll.bind': ['accessoryArgument'],
            'poll.answerBind': ['holderArgument', 'positionArgument'],
            'poll.textSet': ['contentArgument'], 'poll.accessibilitySet': ['contentArgument']
        }[id] || [];
        for (const key of indexes) index(spec, key, count, id);
        if (id === 'message.render') {
            check(spec.identitySource === 'argument', `${id}: identitySource`);
            if (target.static) index(spec, 'viewArgument', count, id);
        }
        if (id === 'embed.bind') {
            check(target.static === false, `${id}: instance required`);
            const direct = spec.inputSource === 'arguments';
            check(direct || spec.inputSource === 'accessory', `${id}: inputSource`);
            for (const key of direct ? ['modelArgument', 'messageIdArgument'] : ['accessoryArgument'])
                index(spec, key, count, id);
            check(direct ? spec.modelArgument !== spec.messageIdArgument && !Object.hasOwn(spec, 'accessoryArgument')
                : !Object.hasOwn(spec, 'modelArgument') && !Object.hasOwn(spec, 'messageIdArgument'), `${id}: conflicting inputs`);
            check(object(spec.host) && (spec.host.source === 'this' && !Object.hasOwn(spec.host, 'field')
                || spec.host.source === 'field' && exact(spec.host.field)), `${id}: unique host`);
        }
        if (id.startsWith('poll.')) {
            check(spec.enabled === true && target.static === false, `${id}: enabled instance required`);
            const paths = id === 'poll.bind'
                ? ['optionsPath', 'messageIdPath', 'textPath', 'hostPath', 'textViewPath']
                : id === 'poll.answerBind' ? ['itemsPath', 'messageIdPath', 'answerIdPath',
                    'textPath', 'holderBindingPath', 'textViewPath', 'imageViewPath'] : [];
            for (const key of paths) fieldPath(spec[key], `${id}.${key}`);
            if (id === 'poll.answerBind') {
                check(spec.holderArgument !== spec.positionArgument, `${id}: distinct holder/position`);
                check(exact(spec.textBindingClass) && exact(spec.imageBindingClass)
                    && spec.textBindingClass !== spec.imageBindingClass, `${id}: distinct binding classes`);
            }
        }
    }
    for (const section of ['types', 'typeAliases', 'members']) {
        if (profile[section] !== undefined) check(object(profile[section])
            && Object.entries(profile[section]).every(([key, value]) => exact(key) && exact(value)), section);
    }
    for (const section of ['fields', 'accessors']) {
        if (profile[section] !== undefined) check(object(profile[section])
            && Object.entries(profile[section]).every(([key, entries]) => exact(key) && object(entries)
                && Object.entries(entries).every(([name, value]) => exact(name) && exact(value))), section);
    }
    const click = profile.iconClick;
    check(object(click) && click.enabled === true && click.protocol === 'constructorProxy'
        && exact(click.spanClass) && exact(click.requiredSuperclass)
        && Array.isArray(click.interfaces) && click.interfaces.every(exact), 'iconClick native span');
    const ctor = click?.constructor;
    validateConstructor(ctor, 'iconClick.constructor', true);
    if (profile.nodeConstructors !== undefined) {
        check(object(profile.nodeConstructors), 'nodeConstructors object');
        if (object(profile.nodeConstructors)) for (const [semantic, spec] of Object.entries(profile.nodeConstructors)) {
            check(exact(profile.types?.[semantic]) || Object.values(profile.typeAliases || {}).includes(semantic),
                `nodeConstructors.${semantic}: mapped semantic type`);
            validateConstructor(spec, `nodeConstructors.${semantic}`, false);
        }
    }
}
function validateConstructor(spec, where, click) {
    if (!object(spec) || !Array.isArray(spec.parameterTypes) || !Array.isArray(spec.arguments)) {
        check(false, `${where}: parameterTypes/arguments arrays`); return;
    }
    check(spec.parameterTypes.length === spec.arguments.length && spec.parameterTypes.every(type => exact(type) && type !== 'void'), `${where}: signature`);
    let special = 0;
    spec.arguments.forEach((arg, i) => {
        const type = spec.parameterTypes[i];
        if (!object(arg)) { check(false, `${where}: argument ${i}`); return; }
        const kinds = click ? ['null', 'integer', 'float', 'callback'] : ['children', 'field', 'integer'];
        check(kinds.includes(arg.kind), `${where}: argument ${i} kind`);
        if (arg.kind === 'integer') check((click ? exact(type) : type === 'int')
            && integer(arg.value), `${where}: integer ${i}`);
        if (arg.kind === 'float') check(exact(type) && typeof arg.value === 'number'
            && Number.isFinite(arg.value) && Number.isFinite(Math.fround(arg.value)), `${where}: finite float ${i}`);
        if (arg.kind === 'field') check(exact(arg.name), `${where}: field ${i}`);
        if (arg.kind === 'children') { special++; check(type === 'java.util.List', `${where}: List children`); }
        if (arg.kind === 'callback') {
            special++;
            check(exact(arg.interface) && exact(arg.method)
                && arg.action === 'toggleTranslation', `${where}: callback ${i}`);
        }
    });
    check(special === 1, `${where}: exactly one ${click ? 'callback' : 'children'}`);
}
try {
    const file = path.join(root, 'app/src/main/assets/hook-config', `${version}.json`);
    validate(JSON.parse(fs.readFileSync(file, 'utf8')));
} catch (error) {
    console.error(`Profile could not be read: ${error.name}`);
    process.exit(1);
}
if (errors.length) {
    errors.forEach(error => console.error(`Invalid metadata: ${error}`));
    process.exit(1);
}
console.log(`Profile ${version}: authoring metadata valid; APK signatures and device behavior not checked.`);
