import { test } from 'node:test';
import assert from 'node:assert/strict';
import { areaHa, culturesOf, fieldErrors, fieldLabel, parseAreaSqM, sortFields } from '../src/lib/fields.ts';

test('площадь: запятая, точка, четыре знака, ноль', () => {
  assert.equal(parseAreaSqM('12,5'), 125000);
  assert.equal(parseAreaSqM('12.5'), 125000);
  assert.equal(parseAreaSqM('0,0001'), 1);
  assert.equal(parseAreaSqM('0'), null);
  assert.equal(parseAreaSqM(''), null);
  assert.equal(parseAreaSqM('abc'), null);
  assert.equal(parseAreaSqM('1,23456'), null, 'пять знаков — больше, чем нужно');
  assert.equal(parseAreaSqM('100000'), 1_000_000_000, '100 000 га — предел');
  assert.equal(parseAreaSqM('100001'), null, 'больше 100 000 га — не поле');
});

test('показ площади — до четырёх знаков без хвостовых нулей', () => {
  assert.equal(areaHa(125000), '12,5');
  assert.equal(areaHa(10000), '1');
  assert.equal(areaHa(12345), '1,2345');
  assert.equal(fieldLabel('Поле 3', 125000), 'Поле 3 — 12,5 га');
});

test('проверки: пустое имя, пробельное имя, длинное имя, площадь', () => {
  const areaError = 'Площадь — целое число м² больше нуля, не больше 100 000 га';
  assert.deepEqual(fieldErrors({ name: '', areaSqM: 0 }), ['Название поля — от 1 до 60 символов', areaError]);
  assert.deepEqual(fieldErrors({ name: '   ', areaSqM: 100 }), ['Название поля — от 1 до 60 символов']);
  assert.deepEqual(fieldErrors({ name: 'я'.repeat(61), areaSqM: 100 }), ['Название поля — от 1 до 60 символов']);
  assert.deepEqual(fieldErrors({ name: 'Поле 1', areaSqM: 12.5 }), [areaError]);
  assert.deepEqual(fieldErrors({ name: 'Поле 1', areaSqM: 1_000_000_001 }), [areaError]);
  assert.deepEqual(fieldErrors({ name: 'Поле 1', areaSqM: 100, culture: 'к'.repeat(61) }), ['Культура — до 60 символов']);
  assert.deepEqual(fieldErrors({ name: 'Поле 1', areaSqM: 100 }), []);
});

test('сортировка по названию по-русски и список культур', () => {
  const rows = [
    { id: '1', data: { name: 'Яблоневый', areaSqM: 100, culture: 'Пшеница' } },
    { id: '2', data: { name: 'Ближний', areaSqM: 100, culture: '' } },
    { id: '3', data: { name: 'Верхний', areaSqM: 100, culture: 'Овёс' } },
  ];
  assert.deepEqual(sortFields(rows).map((r) => r.id), ['2', '3', '1']);
  assert.deepEqual(culturesOf(rows), ['Овёс', 'Пшеница']);
});
