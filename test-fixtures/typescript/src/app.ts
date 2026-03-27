import { connect } from './db';
import { log } from './util';

export function start(): void {
  const conn = connect();
  log('App started');
}
