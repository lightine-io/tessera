/**
 * The triage gate (TES-389). A YouTrack on-change rule for project TES.
 *
 * This file is the source; the rule runs only once the owner has pasted it into a workflow
 * attached to the project (YouTrack: Workflows, new workflow, new "on-change" rule).
 * Rules: .claude/how-we-work.md, "Triage"; .claude/issue-style.md.
 *
 * It refuses two moves:
 *  1. Out of New (to anything but Rejected) while Type, Platform or class of service is
 *     empty, or while a product card (one with a Service) has no Goal.
 *  2. Into Review, or into Test, Staging or Done from a stage before Review, while the
 *     description does not start with the Outcome block.
 * A refused move is rolled back and the message is shown to whoever made it. The board
 * automation (youtrack-sync.yml) moves cards too; a move it makes is refused the same way,
 * so the card stays where it was and the missing content is visible.
 */
const entities = require('@jetbrains/youtrack-scripting-api/entities');
const workflow = require('@jetbrains/youtrack-scripting-api/workflow');

const OUTCOME = '> **Outcome**';
const AFTER_REVIEW = ['Review', 'Test', 'Staging', 'Done'];

exports.rule = entities.Issue.onChange({
  title: 'Triage gate: a card moves only with its required content',
  guard: (ctx) => {
    return ctx.issue.fields.isChanged(ctx.Stage);
  },
  action: (ctx) => {
    const issue = ctx.issue;
    const fields = issue.fields;
    const was = fields.oldValue(ctx.Stage);
    const from = was ? was.name : 'New';
    const to = fields.Stage ? fields.Stage.name : '';

    if (from === 'New' && to !== 'New' && to !== 'Rejected') {
      workflow.check(fields.Type, 'Set Type before the card leaves New.');
      workflow.check(fields.Platform, 'Set Platform before the card leaves New.');
      workflow.check(fields.ClassOfService, 'Set Class of service before the card leaves New.');
      workflow.check(!fields.Service || fields.Goal,
        'A product card (it has a Service) needs a Goal before it leaves New.');
    }

    const entersReview = to === 'Review';
    const skipsReview = AFTER_REVIEW.indexOf(to) > 0 && AFTER_REVIEW.indexOf(from) < 0;
    if (entersReview || skipsReview) {
      const text = issue.description || '';
      workflow.check(text.indexOf(OUTCOME) === 0,
        'Write the Outcome block at the top of the description before this move ' +
        '(what changed, where it shipped, what follows).');
    }
  },
  requirements: {
    Stage: { type: entities.State.fieldType },
    Type: { type: entities.EnumField.fieldType },
    Platform: { type: entities.EnumField.fieldType },
    ClassOfService: { type: entities.EnumField.fieldType, name: 'Class of service' },
    Service: { type: entities.EnumField.fieldType },
    Goal: { type: entities.EnumField.fieldType }
  }
});
