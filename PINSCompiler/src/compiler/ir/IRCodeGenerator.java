/**
 * @ Author: turk
 * @ Description: Generator vmesne kode.
 */

package compiler.ir;

import static common.RequireNonNull.requireNonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import common.Constants;
import compiler.common.Visitor;
import compiler.frm.Access;
import compiler.frm.Frame;
import compiler.frm.Frame.Label;
import compiler.ir.chunk.Chunk;
import compiler.ir.code.IRNode;
import compiler.ir.code.expr.*;
import compiler.ir.code.stmt.*;
import compiler.parser.ast.def.*;
import compiler.parser.ast.def.FunDef.Parameter;
import compiler.parser.ast.expr.*;
import compiler.parser.ast.type.Array;
import compiler.parser.ast.type.Atom;
import compiler.parser.ast.type.TypeName;
import compiler.seman.common.NodeDescription;
import compiler.seman.type.type.Type;

public class IRCodeGenerator implements Visitor {
    /**
     * Preslikava iz vozlišč AST v vmesno kodo.
     */
    private NodeDescription<IRNode> imcCode;

    /**
     * Razrešeni klicni zapisi.
     */
    private final NodeDescription<Frame> frames;

    /**
     * Razrešeni dostopi.
     */
    private final NodeDescription<Access> accesses;

    /**
     * Razrešene definicije.
     */
    private final NodeDescription<Def> definitions;

    /**
     * Razrešeni tipi.
     */
    private final NodeDescription<Type> types;
    List<String> library = new ArrayList<>(Arrays.asList("print_int","print_str","print_log","rand_int","seed"));

    /**
     * **Rezultat generiranja vmesne kode** - seznam fragmentov.
     */
    public List<Chunk> chunks = new ArrayList<>();

    public IRCodeGenerator(
            NodeDescription<IRNode> imcCode,
            NodeDescription<Frame> frames,
            NodeDescription<Access> accesses,
            NodeDescription<Def> definitions,
            NodeDescription<Type> types) {
        requireNonNull(imcCode, frames, accesses, definitions, types);
        this.types = types;
        this.imcCode = imcCode;
        this.frames = frames;
        this.accesses = accesses;
        this.definitions = definitions;
    }

    private Frame currentFrame;

    @Override
    public void visit(Call call) {
        // Acceptam vse argumente
        call.arguments.forEach(arg -> arg.accept(this));

        // Ce je klic na funkcijo v knjiznici, jo poklicemo
        if (library.contains(call.name)) {
            callLibrary(call);
            return;
        }

        // Shranim trenutni staticni nivo in dobim definicijo funkcije
        var currentLevel = currentFrame.staticLevel;
        var def = definitions.valueFor(call).get();

        frames.valueFor(def).ifPresent(frame -> {
            // Izracunam odmik za shranjevanje starega FP
            var binopOld = new BinopExpr(NameExpr.SP(), new ConstantExpr(frame.oldFPOffset()), BinopExpr.Operator.SUB);
            // Na izracunani odmik shranim stari FP
            var saveFP = new MoveStmt(new MemExpr(binopOld), NameExpr.FP());
            List<IRExpr> args = new ArrayList<>();

            // Ce je funkcija globalna FP ni potreben, simbolicno damo konstanto 0
            if (frame.staticLevel == 1)
                args.add(new ConstantExpr(0));

                // Ce je klicana funkcija gnezdena en nivo ji damo FP
            else if (currentLevel - frame.staticLevel == -1)
                args.add(NameExpr.FP());

                // Ce je klicana funkcija gnezdena vec kot en nivo ji damo toliko MEM[FP]
                // kolikor je razlike med nivoji
            else {
                var diff = Math.abs(currentLevel - frame.staticLevel);
                var FP = new MemExpr(NameExpr.FP());

                while (diff > 0) {
                    FP = new MemExpr(FP);
                    diff--;
                }
                args.add(FP);
            }

            // Dodamo vse argumente
            call.arguments.forEach(arg -> args.add((IRExpr) imcCode.valueFor(arg).get()));

            imcCode.store(new EseqExpr(saveFP, new CallExpr(frame.label, args)), call);
        });
    }

    private void callLibrary(Call call) {
        // Ce je klic na funkcijo v knjiznici, jo poklicemo
        List<IRExpr> args = new ArrayList<>();
        // Ker so klicane funkcije tehnicno globalne dobi simbolicno konstanto 0
        args.add(new ConstantExpr(0));
        args.addAll(call.arguments.stream().map(arg -> (IRExpr) imcCode.valueFor(arg).get()).toList());
        imcCode.store(new CallExpr(Frame.Label.named(call.name), args), call);
    }

    @Override
    public void visit(Binary binary) {
        // Sprejmemo levo in desno stran
        binary.left.accept(this);
        binary.right.accept(this);
        var left = imcCode.valueFor(binary.left).get();
        var right = imcCode.valueFor(binary.right).get();

        // Ce je operator = izvedemo Move operacijo
        if (binary.operator.equals(Binary.Operator.ASSIGN)) {
            imcCode.store(new EseqExpr(new MoveStmt((IRExpr) left, (IRExpr) right), (IRExpr) left), binary);
        } else if (binary.operator.equals(Binary.Operator.ARR)) {
            // Ce je operator za tabelo locimo dva primera
            types.valueFor(binary).ifPresent(typeOfArray -> {
                var offset = new BinopExpr((IRExpr) right, new ConstantExpr(typeOfArray.sizeInBytes()),
                        BinopExpr.Operator.MUL);

                var location = new BinopExpr((IRExpr) left, offset, BinopExpr.Operator.ADD);

                if (typeOfArray.isArray()) {
                    // Ce je tip tabele tabela se shrani naslov tabele (multi dimenzionalna tabela)
                    imcCode.store(location, binary);
                } else
                    // Ce je tip tabele atomarni tip se shrani naslov prvega elementa tabele
                    imcCode.store(new MemExpr(location), binary);
            });
        } else {
            // Ce je operator eden od ostalih (+, -, /, *, <=, ==, ...) izvedemo to operacijo
            imcCode.store(new BinopExpr((IRExpr) left, (IRExpr) right,
                    BinopExpr.Operator.valueOf(binary.operator.toString())), binary);
        }
    }

    @Override
    public void visit(Block block) {
        // Sprejmi vse izraze v bloku
        block.expressions.forEach(expr -> expr.accept(this));
        // Zberi vse izraze razen zadnjega in jih pretvori v IRExpr ali IRStmt
        var statements = block.expressions.stream().limit(block.expressions.size() - 1)
                .map(expr -> imcCode.valueFor(expr).get() instanceof IRStmt irStmt ? irStmt
                        : new ExpStmt((IRExpr) imcCode.valueFor(expr).get()))
                .toList();

        var last = (IRExpr) imcCode.valueFor(block.expressions.get(block.expressions.size() - 1)).get();

        imcCode.store(new EseqExpr(new SeqStmt(statements), last), block);
    }

    @Override
    public void visit(For forLoop) {
        // Sprejmi vse izraze v for zanki
        forLoop.counter.accept(this);
        forLoop.low.accept(this);
        forLoop.high.accept(this);
        forLoop.step.accept(this);
        forLoop.body.accept(this);

        var statements = new ArrayList<IRStmt>();

        // Ustvari labele za skok na zacetek zanke, skok na telo zanke in skok na konec
        // zanke
        var label0 = new LabelStmt(Label.nextAnonymous());
        var label1 = new LabelStmt(Label.nextAnonymous());
        var label2 = new LabelStmt(Label.nextAnonymous());

        // Izracunaj pogoj za skok na telo zanke ali konec zanke
        var cond = new BinopExpr((IRExpr) imcCode.valueFor(forLoop.counter).get(),
                (IRExpr) imcCode.valueFor(forLoop.high).get(), BinopExpr.Operator.LT);

        // Nastavi zacetno vrednost stevca
        statements.add(new MoveStmt((IRExpr) imcCode.valueFor(forLoop.counter).get(),
                (IRExpr) imcCode.valueFor(forLoop.low).get()));
        // Dodaj labelo za začetek zanke
        statements.add(label0);
        // Dodaj pogoj za skok na telo zanke ali konec zanke
        statements.add(new CJumpStmt(cond, label1.label, label2.label));
        // Dodaj labelo za telo zanke
        statements.add(label1);

        // Dodaj telo zanke
        imcCode.valueFor(forLoop.body).ifPresent(body -> {
            if (body instanceof IRStmt irStmt)
                statements.add(irStmt);
            else
                statements.add(new ExpStmt((IRExpr) body));
        });

        // Dodaj povecanje stevca
        statements.add(new MoveStmt((IRExpr) imcCode.valueFor(forLoop.counter).get(),
                new BinopExpr((IRExpr) imcCode.valueFor(forLoop.counter).get(),
                        (IRExpr) imcCode.valueFor(forLoop.step).get(), BinopExpr.Operator.ADD)));
        // Dodaj brezpogojni skok na zacetek zanke
        statements.add(new JumpStmt(label0.label));
        // Dodaj labelo za konec zanke
        statements.add(label2);

        imcCode.store(new EseqExpr(new SeqStmt(statements), new ConstantExpr(0)), forLoop);
    }

    @Override
    public void visit(Name name) {
        // Preveri ce definicija obstaja
        definitions.valueFor(name).ifPresent(nameValue -> {
            // Preveri ce access obstaja
            accesses.valueFor(nameValue).ifPresent(accessValue -> {
                if (accessValue instanceof Access.Global globalAccess) {
                    var type = types.valueFor(nameValue).get();
                    // Ce je tip imena seznam se dela z naslovom tabele, ne potrebujemo MEM
                    if (type.isArray())
                        imcCode.store(new NameExpr(globalAccess.label), name);
                        // Ce je tip imena karkoli drugega se dela z vsebino, rabimo MEM
                    else
                        imcCode.store(new MemExpr(new NameExpr(globalAccess.label)), name);
                }

                if (accessValue instanceof Access.Parameter parameterAccess) {
                    // Izracunam Static Link in s dobim vrednost spremenljivke
                    var staticLink = calculateStaticLink(parameterAccess.staticLevel, currentFrame.staticLevel);
                    var offset = new BinopExpr(staticLink, new ConstantExpr(parameterAccess.offset),
                            BinopExpr.Operator.ADD);

                    imcCode.store(new MemExpr(offset), name);
                }

                if (accessValue instanceof Access.Local localAccess) {
                    // Izracunam Static Link in s dobim vrednost spremenljivke
                    BinopExpr staticLink = new BinopExpr(calculateStaticLink(localAccess.staticLevel, currentFrame.staticLevel), new ConstantExpr(localAccess.offset), BinopExpr.Operator.ADD);
                    var type = types.valueFor(nameValue).get();
                    if (type.isArray())
                        imcCode.store(staticLink, name);
                    else
                        imcCode.store(new MemExpr(staticLink), name);
                }
            });
        });
    }

    @Override
    public void visit(IfThenElse ifThenElse) {
        // Sprejmi pogoj, then in else
        ifThenElse.condition.accept(this);
        ifThenElse.thenExpression.accept(this);
        ifThenElse.elseExpression.ifPresent(expr -> expr.accept(this));

        var statements = new ArrayList<IRStmt>();

        // Ustvari labelo za then else in konec (else je lahko neuporabljen)
        var label1 = new LabelStmt(Label.nextAnonymous());
        var label2 = new LabelStmt(Label.nextAnonymous());
        var label3 = new LabelStmt(Label.nextAnonymous());

        imcCode.valueFor(ifThenElse.condition).ifPresent(ifValue -> {
            imcCode.valueFor(ifThenElse.thenExpression).ifPresent(thenValue -> {
                // Ce je then izraz je potrebno ustvariti ExpStmt, ce ni se uporabi stavek
                var thenExpr = thenValue instanceof IRStmt irStmt ? irStmt : new ExpStmt((IRExpr) thenValue);

                // Ce else obstaja
                if (ifThenElse.elseExpression.isPresent()) {
                    // Ustvarimo pogojni skok na then in else
                    statements.add(new CJumpStmt((IRExpr) ifValue, label1.label, label2.label));
                    // Dodamo labelo za then
                    statements.add(label1);
                    // Dodamo telo then
                    statements.add(thenExpr);
                    // Dodamo brezpogojni skok na konec
                    statements.add(new JumpStmt(label3.label));
                    // Dodamo labelo za else
                    statements.add(label2);
                    // Ce je else izraz je potrebno ustvariti ExpStmt, ce ni se uporabi stavek
                    imcCode.valueFor(ifThenElse.elseExpression.get()).ifPresent(elseValue -> {
                        if (elseValue instanceof IRStmt irStmt)
                            statements.add(irStmt);
                        else
                            statements.add(new ExpStmt((IRExpr) elseValue));
                    });
                    // Dodamo labelo za konec
                    statements.add(label3);
                } else {
                    // Ce else ne obstaja
                    // Dodamo pogojni skok na then in konec
                    statements.add(new CJumpStmt((IRExpr) ifValue, label1.label, label3.label));
                    // Dodamo labelo za then
                    statements.add(label1);
                    // Dodamo telo then
                    statements.add(thenExpr);
                    // Dodamo labelo za konec
                    statements.add(label3);
                }

                imcCode.store(new EseqExpr(new SeqStmt(statements), new ConstantExpr(0)), ifThenElse);
            });
        });
    }

    @Override
    public void visit(Literal literal) {
        types.valueFor(literal).ifPresent(type -> {
            // Ce je literal int se ustvari konstanta z vrednostjo literala
            if (type.isInt())
                imcCode.store(new ConstantExpr(Integer.parseInt(literal.value)), literal);
                // Ce je literal logical se ustvari konstanta z vrednostjo literala
            else if (type.isLog())
                imcCode.store(new ConstantExpr(literal.value.equals("true") ? 1 : 0), literal);
            else if (type.isStr()) {
                // Ce je literal string se zanj ustvari labela in se ustvari data chunk
                Label label = Label.nextAnonymous();
                var dataChunk = new Chunk.DataChunk(new Access.Global(Constants.WordSize, label), literal.value);
                chunks.add(dataChunk);
                imcCode.store(new NameExpr(label), literal);
            }
        });
    }

    @Override
    public void visit(Unary unary) {
        unary.expr.accept(this);
        imcCode.valueFor(unary.expr).ifPresent(unaryValue -> {
            // Ce je operator + gre za predznak in simbolicno sestejemo z
            // konstanto 0
            if (unary.operator.equals(Unary.Operator.ADD))
                imcCode.store(new BinopExpr(new ConstantExpr(0), (IRExpr) unaryValue, BinopExpr.Operator.ADD), unary);
            else if (unary.operator.equals(Unary.Operator.SUB))
                // Ce je operator - gre za predznak in konstanto odstejemo od nic da dobimo
                // negativno stevilo
                imcCode.store(new BinopExpr(new ConstantExpr(0), (IRExpr) unaryValue, BinopExpr.Operator.SUB), unary);
            else if (unary.operator.equals(Unary.Operator.NOT)) {
                // Ce je operator ! gre za negacijo in vrednost odstejemo od 1 da dobimo
                // ustrezno logicno vrednost
                imcCode.store(new BinopExpr(new ConstantExpr(1), (IRExpr) unaryValue, BinopExpr.Operator.SUB), unary);
            }
        });
    }

    @Override
    public void visit(While whileLoop) {
        // Sprejmemo pogoj in telo
        whileLoop.condition.accept(this);
        whileLoop.body.accept(this);

        List<IRStmt> statements = new ArrayList<IRStmt>();

        // Usrvarimo labelo za zacetek, telo in konec
        var label0 = new LabelStmt(Label.nextAnonymous());
        var label1 = new LabelStmt(Label.nextAnonymous());
        var label2 = new LabelStmt(Label.nextAnonymous());

        // Dodamo labelo za zacetek
        statements.add(label0);
        // Dodamo pogojni skok na telo ali kone
        statements.add(new CJumpStmt((IRExpr) imcCode.valueFor(whileLoop.condition).get(), label1.label, label2.label));
        // Dodamo labelo za telo
        statements.add(label1);
        // Ce je telo izraz je potrebno ustvariti ExpStmt, ce ni se uporabi stavek
        imcCode.valueFor(whileLoop.body).ifPresent(bodyValue -> {
            if (bodyValue instanceof IRStmt irStmt)
                statements.add(irStmt);
            else
                statements.add(new ExpStmt((IRExpr) bodyValue));
        });

        // Dodamo brezpogojni skok na zacetek
        statements.add(new JumpStmt(label0.label));
        // Dodamo labelo za konec
        statements.add(label2);

        imcCode.store(new EseqExpr(new SeqStmt(statements), new ConstantExpr(0)), whileLoop);
    }

    @Override
    public void visit(Where where) {
        // Sprejmemo definicije in izraz
        where.defs.accept(this);
        where.expr.accept(this);

        imcCode.valueFor(where.expr).ifPresent(exprValue -> {
            imcCode.store(exprValue, where);
        });
    }

    @Override
    public void visit(Defs defs) {
        defs.definitions.forEach(def -> def.accept(this));
    }

    @Override
    public void visit(FunDef funDef) {
        // Shranimo star frame in nastavimo nov trenutni frame
        Frame oldFrame = this.currentFrame;
        this.currentFrame = frames.valueFor(funDef).get();
        // Sprejmemo telo funkcije
        funDef.body.accept(this);

        // Izraz je potrebno return value shraniti v FP
        imcCode.valueFor(funDef.body).ifPresent(bodyValue -> chunks.add(new Chunk.CodeChunk(currentFrame, new MoveStmt(new MemExpr(NameExpr.FP()), (IRExpr) bodyValue))));
        this.currentFrame = oldFrame;
    }

    @Override
    public void visit(TypeDef typeDef) {
    }

    @Override
    public void visit(VarDef varDef) {
        // Ce je access globlen ustvarimo globalni chunk
        accesses.valueFor(varDef).ifPresent(varValue -> {
            if (varValue instanceof Access.Global varGlobal) {
                chunks.add(new Chunk.GlobalChunk(varGlobal));
            }
        });
    }

    @Override
    public void visit(Parameter parameter) {
    }

    @Override
    public void visit(Array array) {
    }

    @Override
    public void visit(Atom atom) {
    }

    @Override
    public void visit(TypeName name) {
    }

    private IRExpr calculateStaticLink(int targetLevel, int frameLevel) {
        var diff = Math.abs(targetLevel - frameLevel);
        // Ce je razlika 0 je static link kar FP
        if (diff == 0)
            return NameExpr.FP();

        // Ce je razlika vecja od 0 potrebujemo toliko dereferenciranj kolikor je razlika
        var location = new MemExpr(NameExpr.FP());
        diff--;

        while (diff > 0) {
            location = new MemExpr(location);
            diff--;
        }

        return location;
    }
}
